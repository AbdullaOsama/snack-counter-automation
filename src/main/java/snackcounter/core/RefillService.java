package snackcounter.core;

import snackcounter.domain.Money;
import snackcounter.domain.OfficeConfig;
import snackcounter.domain.OfficeId;
import snackcounter.domain.Receipt;
import snackcounter.domain.Volunteer;
import snackcounter.ports.ChatGateway;
import snackcounter.ports.ClaimRequest;
import snackcounter.ports.ClaimStatus;
import snackcounter.ports.HrSystem;
import snackcounter.ports.PresenceCalendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The refill lifecycle. Every chat button maps to one public command here; {@link #tick()} is called by a
 * scheduler (e.g. every 5 minutes) to handle timeouts, retry HR submits and poll HR for approvals.
 *
 * <p>Concurrency: all commands are {@code synchronized}, so "check state, then change it" is atomic and two
 * people can never both commit to the same refill. With a database this becomes a conditional update
 * ({@code UPDATE refill SET state='COMMITTED', assignee=? WHERE id=? AND state IN ('OPEN','OFFERED') ...})
 * and the loser gets 0 rows updated, which is the same {@link RefillConflictException}.
 */
public final class RefillService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private final Map<OfficeId, OfficeConfig> offices = new EnumMap<>(OfficeId.class);
    private final Map<String, Volunteer> volunteers = new LinkedHashMap<>();
    private final PresenceCalendar presence;
    private final ChatGateway chat;
    private final HrSystem hr;
    private final Clock clock;
    private final Policy policy;
    private final VolunteerPicker picker = new VolunteerPicker();
    private final Map<String, Refill> refills = new LinkedHashMap<>();
    private int nextId = 1;

    public RefillService(Collection<OfficeConfig> offices, Collection<Volunteer> volunteers,
                         PresenceCalendar presence, ChatGateway chat, HrSystem hr, Clock clock, Policy policy) {
        offices.forEach(o -> this.offices.put(o.id(), o));
        volunteers.forEach(v -> this.volunteers.put(v.employeeId(), v));
        this.presence = Objects.requireNonNull(presence);
        this.chat = Objects.requireNonNull(chat);
        this.hr = Objects.requireNonNull(hr);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
    }

    // =====================================================================================
    // Commands (one per chat action)
    // =====================================================================================

    /**
     * Anyone (not only volunteers) can press "the box is empty". A second report while a refill is already
     * in progress does NOT start another one; the reporter is told who is on it.
     */
    public synchronized Refill reportEmpty(OfficeId officeId, String reporterId, List<String> ranOutItems) {
        OfficeConfig office = office(officeId);
        Instant now = clock.instant();

        Optional<Refill> existing = activeRefill(officeId);
        if (existing.isPresent()) {
            Refill r = existing.get();
            r.addRanOut(ranOutItems);
            r.log(now, "duplicate empty report from " + reporterId);
            chat.sendDirect(reporterId, "Thanks! The " + office.name() + " box is already being handled: "
                    + describe(r) + ".");
            return r;
        }

        Refill r = new Refill("R-" + nextId++, officeId, reporterId, now, ranOutItems);
        refills.put(r.id(), r);

        Money limit = Budget.spendingLimit(office, monthOf(office, now), refills.values());
        if (!Budget.enoughForARefill(office, limit)) {
            r.setSpendingLimit(limit);
            r.moveTo(RefillState.ON_HOLD_BUDGET, now, "only " + limit + " left this month");
            chat.postToChannel(office.adminChannel(), "Budget: the " + office.name() + " box is empty but only "
                    + limit + " is left of this month's budget. " + r.id() + " is on hold until next month "
                    + "unless you raise the budget.");
            return r;
        }
        assignNext(r, office, now, null);
        return r;
    }

    /** "Yes, I'll buy." Only the person it was offered to may accept, or anyone during an open call. */
    public synchronized void accept(String refillId, String employeeId) {
        Refill r = refill(refillId);
        Volunteer v = volunteer(employeeId);
        OfficeConfig office = office(r.office());
        Instant now = clock.instant();

        switch (r.state()) {
            case OFFERED -> {
                if (!employeeId.equals(r.assignee())) {
                    throw new RefillConflictException(refillId + " is offered to " + r.assignee() + ", not " + employeeId);
                }
            }
            case OPEN -> r.setSpendingLimit(Budget.spendingLimit(office, monthOf(office, now), refills.values()));
            default -> throw new RefillConflictException(refillId + " can no longer be accepted (" + describe(r) + ")");
        }

        r.moveTo(RefillState.COMMITTED, now, "accepted by " + employeeId);
        r.commit(employeeId, now);
        Instant deadline = now.plus(policy.purchaseTimeout());
        chat.sendDirect(employeeId, "Great, you're buying for " + office.name() + ". Spend up to "
                + r.spendingLimit() + " and upload the receipt here before " + fmt(deadline, office.zone())
                + ". [" + r.id() + "]");
        chat.postToChannel(office.channel(), v.name() + " is refilling the " + office.name()
                + " snack box. No need for anyone else to buy.");
    }

    /** "No, not today." Passes straight to the next person. */
    public synchronized void decline(String refillId, String employeeId) {
        Refill r = refill(refillId);
        if (r.state() != RefillState.OFFERED || !employeeId.equals(r.assignee())) {
            throw new RefillConflictException(refillId + " is not offered to " + employeeId);
        }
        r.exclude(employeeId);
        assignNext(r, office(r.office()), clock.instant(), employeeId + " declined");
    }

    /** "I accepted but can't make it after all." Better than silently timing out. */
    public synchronized void backOut(String refillId, String employeeId) {
        Refill r = refill(refillId);
        if (r.state() != RefillState.COMMITTED || !employeeId.equals(r.assignee())) {
            throw new RefillConflictException(refillId + " is not held by " + employeeId);
        }
        r.exclude(employeeId);
        assignNext(r, office(r.office()), clock.instant(), employeeId + " backed out");
    }

    /**
     * Receipt upload. Only the committed buyer may submit, in the office's currency. Within the limit the
     * HR claim is filed immediately; over the limit it waits for an admin (the money is already spent,
     * so we escalate rather than refuse).
     */
    public synchronized Refill submitReceipt(String refillId, String employeeId, Receipt receipt) {
        Refill r = refill(refillId);
        OfficeConfig office = office(r.office());
        Instant now = clock.instant();

        if (r.state() != RefillState.COMMITTED || !employeeId.equals(r.assignee())) {
            throw new RefillConflictException("Receipt rejected: " + refillId + " is not committed to "
                    + employeeId + " (" + describe(r) + ")");
        }
        if (!receipt.total().currency().equals(office.currency())) {
            throw new IllegalArgumentException("Receipt for " + office.name() + " must be in "
                    + office.currency().getCurrencyCode() + ", got " + receipt.total().currency().getCurrencyCode());
        }
        if (!receipt.total().isPositive()) {
            throw new IllegalArgumentException("Receipt total must be positive");
        }

        r.recordPurchase(employeeId, receipt, now);
        if (receipt.total().isGreaterThan(r.spendingLimit())) {
            r.moveTo(RefillState.NEEDS_APPROVAL, now, receipt.total() + " > limit " + r.spendingLimit());
            chat.postToChannel(office.adminChannel(), "Approval needed: " + volunteer(employeeId).name()
                    + " spent " + receipt.total() + " on " + r.id() + " (limit " + r.spendingLimit() + ").");
            chat.sendDirect(employeeId, "Thanks for refilling! The receipt is above the " + r.spendingLimit()
                    + " limit, so an admin will approve it before it goes to HR.");
        } else {
            r.moveTo(RefillState.PURCHASED, now, receipt.total().toString());
            submitClaim(r, office, now);
        }
        chat.postToChannel(office.channel(), "The " + office.name() + " snack box has been refilled by "
                + volunteer(employeeId).name() + ". Thank you!");
        return r;
    }

    /** Admin approves an over-limit receipt; the claim then goes to HR. */
    public synchronized void approveOverspend(String refillId, String adminId) {
        Refill r = refill(refillId);
        if (r.state() != RefillState.NEEDS_APPROVAL) {
            throw new RefillConflictException(refillId + " does not need approval (" + r.state() + ")");
        }
        Instant now = clock.instant();
        r.moveTo(RefillState.PURCHASED, now, "over-limit approved by " + adminId);
        submitClaim(r, office(r.office()), now);
    }

    /**
     * Called periodically. Idempotent: running it twice in a row changes nothing the second time.
     */
    public synchronized void tick() {
        Instant now = clock.instant();
        for (Refill r : List.copyOf(refills.values())) {
            OfficeConfig office = office(r.office());
            switch (r.state()) {
                case OFFERED -> {
                    if (reached(now, r.offeredAt(), policy.offerTimeout())) {
                        String who = r.assignee();
                        r.exclude(who);
                        chat.sendDirect(who, "No answer in time, so the " + office.name()
                                + " refill was passed to someone else. No action needed. [" + r.id() + "]");
                        assignNext(r, office, now, "offer to " + who + " timed out");
                    }
                }
                case COMMITTED -> {
                    if (reached(now, r.committedAt(), policy.purchaseTimeout())) {
                        String who = r.assignee();
                        r.exclude(who);
                        chat.sendDirect(who, "No receipt within " + human(policy.purchaseTimeout())
                                + ", so the " + office.name() + " refill has been handed to someone else. "
                                + "Please do NOT buy for " + r.id() + " now.");
                        assignNext(r, office, now, who + " did not complete in time");
                    }
                }
                case OPEN -> {
                    // Someone may have arrived at the office since the open call went out.
                    assignNext(r, office, now, "volunteer now available");
                    if (r.state() == RefillState.OPEN && !r.openCallEscalated()
                            && reached(now, r.openSince(), policy.openCallEscalation())) {
                        r.markOpenCallEscalated();
                        r.log(now, "open call escalated to admins");
                        chat.postToChannel(office.adminChannel(), "Nobody has picked up " + r.id() + " for "
                                + office.name() + " in " + human(policy.openCallEscalation()) + ". Can you help?");
                    }
                }
                case ON_HOLD_BUDGET -> {
                    Money limit = Budget.spendingLimit(office, monthOf(office, now), refills.values());
                    if (Budget.enoughForARefill(office, limit)) {
                        r.moveTo(RefillState.OPEN, now, "budget available again");
                        r.reopen(now);
                        assignNext(r, office, now, null);
                    }
                }
                case PURCHASED -> submitClaim(r, office, now); // retry after an HR outage
                case CLAIM_SUBMITTED -> pollClaim(r, office, now);
                default -> {
                }
            }
        }
    }

    // =====================================================================================
    // Queries
    // =====================================================================================

    public synchronized Refill refill(String refillId) {
        Refill r = refills.get(refillId);
        if (r == null) {
            throw new NoSuchElementException("No refill " + refillId);
        }
        return r;
    }

    public synchronized List<Refill> refills() {
        return List.copyOf(refills.values());
    }

    public synchronized Optional<Refill> activeRefill(OfficeId officeId) {
        return refills.values().stream()
                .filter(r -> r.office() == officeId && r.state().boxStillEmpty())
                .findFirst();
    }

    public synchronized Money spent(OfficeId officeId, YearMonth month) {
        return Budget.spent(office(officeId), month, refills.values());
    }

    public synchronized Money remainingBudget(OfficeId officeId, YearMonth month) {
        return Budget.remaining(office(officeId), month, refills.values());
    }

    public Collection<OfficeConfig> offices() {
        return offices.values();
    }

    public Volunteer volunteer(String employeeId) {
        Volunteer v = volunteers.get(employeeId);
        if (v == null) {
            throw new NoSuchElementException("Not a registered volunteer: " + employeeId);
        }
        return v;
    }

    // =====================================================================================
    // Internals
    // =====================================================================================

    /** Offer to the fairest available person, or fall back to an open call in the office channel. */
    private void assignNext(Refill r, OfficeConfig office, Instant now, String reason) {
        Money limit = Budget.spendingLimit(office, monthOf(office, now), refills.values());
        r.setSpendingLimit(limit);

        Optional<Volunteer> next = picker.pick(candidatesFor(r, office, now));
        if (next.isPresent()) {
            Volunteer v = next.get();
            String note = (reason == null ? "" : reason + "; ") + "offered to " + v.employeeId();
            r.moveTo(RefillState.OFFERED, now, note);
            r.offerTo(v.employeeId(), now);
            chat.sendDirect(v.employeeId(), "Hi " + v.name() + ", the " + office.name()
                    + " snack box is empty and it's your turn. Can you buy snacks (up to " + limit
                    + ") within " + human(policy.purchaseTimeout()) + "? Please accept or decline within "
                    + human(policy.offerTimeout()) + ". [" + r.id() + "]");
            return;
        }
        if (r.state() != RefillState.OPEN) {
            r.moveTo(RefillState.OPEN, now, (reason == null ? "" : reason + "; ") + "no one eligible in office");
            r.reopen(now);
        }
        if (!r.openCallAnnounced()) {
            r.markOpenCallAnnounced();
            chat.postToChannel(office.channel(), "Open call: the " + office.name()
                    + " snack box is empty and no scheduled volunteer is in today. The first to accept "
                    + r.id() + " buys (up to " + limit + ").");
        }
    }

    private List<VolunteerPicker.Candidate> candidatesFor(Refill r, OfficeConfig office, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, office.zone());
        Instant windowStart = now.minus(policy.fairnessWindow());
        return volunteers.values().stream()
                .filter(v -> presence.on(v.employeeId(), today).isAt(office.id()))
                .filter(v -> !r.excluded().contains(v.employeeId()))
                .filter(v -> !holdsAnotherRefill(v.employeeId(), r))
                .map(v -> statsFor(v, windowStart))
                .toList();
    }

    private boolean holdsAnotherRefill(String employeeId, Refill current) {
        return refills.values().stream().anyMatch(o -> o != current
                && (o.state() == RefillState.OFFERED || o.state() == RefillState.COMMITTED)
                && employeeId.equals(o.assignee()));
    }

    private VolunteerPicker.Candidate statsFor(Volunteer v, Instant windowStart) {
        int recent = 0;
        boolean awaiting = false;
        Instant last = null;
        for (Refill o : refills.values()) {
            if (!v.employeeId().equals(o.purchaserId()) || !o.state().countsAgainstBudget()) {
                continue;
            }
            if (o.purchasedAt().isAfter(windowStart)) {
                recent++;
            }
            awaiting |= o.state().awaitingReimbursement();
            if (last == null || o.purchasedAt().isAfter(last)) {
                last = o.purchasedAt();
            }
        }
        return new VolunteerPicker.Candidate(v, recent, awaiting, last);
    }

    private void submitClaim(Refill r, OfficeConfig office, Instant now) {
        Receipt receipt = r.receipt();
        String description = "Snack refill " + r.id() + ", " + office.name() + " office, bought "
                + receipt.purchaseDate();
        try {
            String claimId = hr.submitClaim(new ClaimRequest(r.id(), r.purchaserId(), receipt.total(),
                    description, receipt.image()));
            r.recordClaim(claimId, now);
            r.moveTo(RefillState.CLAIM_SUBMITTED, now, "HR claim " + claimId);
            chat.sendDirect(r.purchaserId(), "Your reimbursement claim " + claimId + " for " + receipt.total()
                    + " has been filed with HR automatically. You'll be paid in "
                    + volunteer(r.purchaserId()).payrollCurrency().getCurrencyCode() + " once HR approves it.");
        } catch (RuntimeException e) {
            r.log(now, "HR submit failed (" + e.getMessage() + "), will retry");
        }
    }

    private void pollClaim(Refill r, OfficeConfig office, Instant now) {
        ClaimStatus status;
        try {
            status = hr.status(r.claimId());
        } catch (RuntimeException e) {
            r.log(now, "HR status check failed (" + e.getMessage() + ")");
            return;
        }
        switch (status) {
            case APPROVED -> {
                r.moveTo(RefillState.REIMBURSED, now, "HR approved " + r.claimId());
                r.close(now);
                chat.sendDirect(r.purchaserId(), "HR approved your snack claim " + r.claimId()
                        + ". Thanks for keeping the " + office.name() + " box full!");
            }
            case REJECTED -> {
                r.moveTo(RefillState.CLAIM_REJECTED, now, "HR rejected " + r.claimId());
                r.close(now);
                chat.sendDirect(r.purchaserId(), "HR rejected claim " + r.claimId()
                        + ". An admin has been notified and will follow up with you.");
                chat.postToChannel(office.adminChannel(), "HR rejected claim " + r.claimId() + " (" + r.id()
                        + ", " + volunteer(r.purchaserId()).name() + ", " + r.receipt().total() + "). Please follow up.");
            }
            case PENDING -> {
                if (!r.slowClaimEscalated() && reached(now, r.claimSubmittedAt(), policy.slowClaimThreshold())) {
                    r.markSlowClaimEscalated();
                    r.log(now, "claim pending longer than " + human(policy.slowClaimThreshold()));
                    chat.postToChannel(office.adminChannel(), "Claim " + r.claimId() + " for "
                            + volunteer(r.purchaserId()).name() + " (" + r.receipt().total() + ") has waited "
                            + human(policy.slowClaimThreshold()) + " at HR. Please chase it.");
                }
            }
        }
    }

    private String describe(Refill r) {
        return switch (r.state()) {
            case OFFERED -> "asking " + volunteer(r.assignee()).name();
            case COMMITTED -> volunteer(r.assignee()).name() + " is buying";
            case OPEN -> "open call in " + office(r.office()).channel();
            case ON_HOLD_BUDGET -> "on hold, monthly budget used up";
            default -> r.state().name().toLowerCase();
        };
    }

    private OfficeConfig office(OfficeId id) {
        OfficeConfig o = offices.get(id);
        if (o == null) {
            throw new NoSuchElementException("Unknown office " + id);
        }
        return o;
    }

    private static YearMonth monthOf(OfficeConfig office, Instant now) {
        return YearMonth.from(now.atZone(office.zone()));
    }

    private static boolean reached(Instant now, Instant since, Duration timeout) {
        return since != null && !now.isBefore(since.plus(timeout));
    }

    private static String fmt(Instant at, ZoneId zone) {
        return TIME.format(at.atZone(zone));
    }

    static String human(Duration d) {
        if (d.toDays() >= 1 && d.toHours() % 24 == 0) {
            return d.toDays() + (d.toDays() == 1 ? " day" : " days");
        }
        return d.toHours() + (d.toHours() == 1 ? " hour" : " hours");
    }
}
