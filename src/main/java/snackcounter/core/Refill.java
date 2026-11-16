package snackcounter.core;

import snackcounter.domain.Money;
import snackcounter.domain.OfficeId;
import snackcounter.domain.Receipt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One "the box is empty" episode, from report to reimbursement. Mutators are package-private:
 * only {@link RefillService} may change a refill, and only through {@link #moveTo}.
 * {@link #history()} is an append-only audit trail.
 */
public final class Refill {

    private final String id;
    private final OfficeId office;
    private final String reportedBy;
    private final Instant reportedAt;
    private final List<String> ranOutItems = new ArrayList<>();
    private final Set<String> excluded = new LinkedHashSet<>();
    private final List<String> history = new ArrayList<>();

    private RefillState state = RefillState.OPEN;
    private String assignee;
    private Instant offeredAt;
    private Instant committedAt;
    private Instant openSince;
    private boolean openCallAnnounced;
    private boolean openCallEscalated;
    private Money spendingLimit;
    private Receipt receipt;
    private String purchaserId;
    private Instant purchasedAt;
    private String claimId;
    private Instant claimSubmittedAt;
    private boolean slowClaimEscalated;
    private Instant closedAt;

    Refill(String id, OfficeId office, String reportedBy, Instant reportedAt, List<String> ranOutItems) {
        this.id = id;
        this.office = office;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
        this.openSince = reportedAt;
        this.ranOutItems.addAll(ranOutItems);
        log(reportedAt, "reported empty by " + reportedBy);
    }

    void moveTo(RefillState next, Instant at, String note) {
        if (!state.canMoveTo(next)) {
            throw new IllegalStateException("Refill " + id + ": illegal transition " + state + " -> " + next);
        }
        log(at, state + " -> " + next + (note == null ? "" : " (" + note + ")"));
        state = next;
    }

    void log(Instant at, String text) {
        history.add(at + "  " + text);
    }

    void offerTo(String employeeId, Instant at) {
        assignee = employeeId;
        offeredAt = at;
        committedAt = null;
        openCallAnnounced = false;
    }

    void commit(String employeeId, Instant at) {
        assignee = employeeId;
        committedAt = at;
    }

    void reopen(Instant at) {
        assignee = null;
        offeredAt = null;
        committedAt = null;
        openSince = at;
        openCallEscalated = false;
    }

    void exclude(String employeeId) {
        excluded.add(employeeId);
    }

    void addRanOut(List<String> items) {
        ranOutItems.addAll(items);
    }

    void markOpenCallAnnounced() {
        openCallAnnounced = true;
    }

    void markOpenCallEscalated() {
        openCallEscalated = true;
    }

    void markSlowClaimEscalated() {
        slowClaimEscalated = true;
    }

    void setSpendingLimit(Money limit) {
        spendingLimit = limit;
    }

    void recordPurchase(String employeeId, Receipt r, Instant at) {
        purchaserId = employeeId;
        receipt = r;
        purchasedAt = at;
    }

    void recordClaim(String hrClaimId, Instant at) {
        claimId = hrClaimId;
        claimSubmittedAt = at;
    }

    void close(Instant at) {
        closedAt = at;
    }

    // ---- read-only view ----

    public String id() { return id; }
    public OfficeId office() { return office; }
    public String reportedBy() { return reportedBy; }
    public Instant reportedAt() { return reportedAt; }
    public List<String> ranOutItems() { return Collections.unmodifiableList(ranOutItems); }
    public Set<String> excluded() { return Collections.unmodifiableSet(excluded); }
    public List<String> history() { return Collections.unmodifiableList(history); }
    public RefillState state() { return state; }
    public String assignee() { return assignee; }
    public Instant offeredAt() { return offeredAt; }
    public Instant committedAt() { return committedAt; }
    public Instant openSince() { return openSince; }
    public boolean openCallAnnounced() { return openCallAnnounced; }
    public boolean openCallEscalated() { return openCallEscalated; }
    public Money spendingLimit() { return spendingLimit; }
    public Receipt receipt() { return receipt; }
    public String purchaserId() { return purchaserId; }
    public Instant purchasedAt() { return purchasedAt; }
    public String claimId() { return claimId; }
    public Instant claimSubmittedAt() { return claimSubmittedAt; }
    public boolean slowClaimEscalated() { return slowClaimEscalated; }
    public Instant closedAt() { return closedAt; }

    @Override
    public String toString() {
        return id + "[" + office + ", " + state + (assignee != null ? ", " + assignee : "") + "]";
    }
}
