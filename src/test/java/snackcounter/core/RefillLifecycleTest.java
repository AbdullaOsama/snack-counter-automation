package snackcounter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import snackcounter.domain.Money;
import snackcounter.domain.Presence;
import snackcounter.fakes.FakeHrSystem;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static snackcounter.domain.OfficeId.BENGALURU;
import static snackcounter.domain.OfficeId.OSLO;

class RefillLifecycleTest {

    private final TestWorld w = new TestWorld();

    @Test
    @DisplayName("Happy path: empty -> offered -> committed -> claim filed -> reimbursed")
    void emptyToReimbursed() {
        w.usuallyAt(OSLO, "anna");

        Refill r = w.service.reportEmpty(OSLO, "kari", List.of("chocolate"));
        assertEquals(RefillState.OFFERED, r.state());
        assertEquals("anna", r.assignee());
        assertTrue(w.chat.lastDirectTo("anna").contains("1200.00 NOK"), "offer states the spending limit");

        w.service.accept(r.id(), "anna");
        assertEquals(RefillState.COMMITTED, r.state());
        assertTrue(w.chat.channel("#snacks-oslo").getLast().contains("No need for anyone else to buy"));

        w.service.submitReceipt(r.id(), "anna", w.receipt(OSLO, "800"));
        assertEquals(RefillState.CLAIM_SUBMITTED, r.state());

        FakeHrSystem.Claim claim = w.hr.claimForRefill(r.id()).orElseThrow();
        assertEquals("anna", claim.request().employeeId());
        assertEquals(Money.of(800, Money.NOK), claim.request().amount());
        assertTrue(claim.request().receiptImage().length > 0);

        w.advance(Duration.ofDays(3));
        w.service.tick();
        assertEquals(RefillState.CLAIM_SUBMITTED, r.state(), "still pending at HR");

        w.hr.approve(claim.id());
        w.service.tick();
        assertEquals(RefillState.REIMBURSED, r.state());
        assertTrue(w.chat.lastDirectTo("anna").contains("approved"));
    }

    @Test
    @DisplayName("A second 'box is empty' report does not start a second refill (no double buying)")
    void duplicateReportReusesActiveRefill() {
        w.usuallyAt(OSLO, "anna", "bjorn");

        Refill first = w.service.reportEmpty(OSLO, "kari", List.of("chocolate"));
        Refill second = w.service.reportEmpty(OSLO, "ola", List.of("nuts"));

        assertSame(first, second);
        assertEquals(1, w.service.refills().size());
        assertEquals(List.of("chocolate", "nuts"), first.ranOutItems());
        assertTrue(w.chat.directTo("bjorn").isEmpty(), "only one person is asked");
        assertTrue(w.chat.lastDirectTo("ola").contains("already being handled"));
    }

    @Test
    @DisplayName("Two people cannot both commit to the same refill")
    void onlyTheOfferedPersonCanCommit() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        assertEquals("anna", r.assignee());

        assertThrows(RefillConflictException.class, () -> w.service.accept(r.id(), "bjorn"));
        w.service.accept(r.id(), "anna");
        assertThrows(RefillConflictException.class, () -> w.service.accept(r.id(), "bjorn"));
        assertThrows(RefillConflictException.class, () -> w.service.accept(r.id(), "anna"));
        assertEquals("anna", r.assignee());
    }

    @Test
    @DisplayName("An unanswered offer is handed to the next person after the offer timeout")
    void unansweredOfferMovesOn() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());

        w.advance(Duration.ofMinutes(119));
        w.service.tick();
        assertEquals("anna", r.assignee(), "not yet timed out");

        w.advance(Duration.ofMinutes(1));
        w.service.tick();
        assertEquals(RefillState.OFFERED, r.state());
        assertEquals("bjorn", r.assignee());
        assertTrue(w.chat.lastDirectTo("anna").contains("passed to someone else"));
        assertThrows(RefillConflictException.class, () -> w.service.accept(r.id(), "anna"), "late accept refused");
    }

    @Test
    @DisplayName("A refill someone accepted but never completed is handed to someone else")
    void committedButNotCompletedIsReassigned() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        w.service.accept(r.id(), "anna");

        w.advance(Duration.ofHours(24));
        w.service.tick();

        assertEquals(RefillState.OFFERED, r.state());
        assertEquals("bjorn", r.assignee());
        assertTrue(w.chat.lastDirectTo("anna").contains("do NOT buy"));
        assertThrows(RefillConflictException.class,
                () -> w.service.submitReceipt(r.id(), "anna", w.receipt(OSLO, "500")),
                "released volunteer can no longer submit, so we never pay twice");
    }

    @Test
    @DisplayName("Declining passes the refill on immediately")
    void declineMovesOn() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());

        w.service.decline(r.id(), "anna");

        assertEquals("bjorn", r.assignee());
        assertTrue(r.excluded().contains("anna"));
    }

    @Test
    @DisplayName("Backing out after accepting passes the refill on and tells the channel")
    void backOutMovesOn() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        w.service.accept(r.id(), "anna");

        w.service.backOut(r.id(), "anna");

        assertEquals(RefillState.OFFERED, r.state());
        assertEquals("bjorn", r.assignee());
    }

    @Test
    @DisplayName("When everyone has declined, it becomes an open call where the first to accept wins")
    void everyoneDeclinesThenOpenCall() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        w.service.decline(r.id(), "anna");
        w.service.decline(r.id(), "bjorn");

        assertEquals(RefillState.OPEN, r.state());
        assertNull(r.assignee());
        assertTrue(w.chat.channel("#snacks-oslo").getLast().startsWith("Open call"));

        w.service.accept(r.id(), "carl");
        assertEquals(RefillState.COMMITTED, r.state());
        assertThrows(RefillConflictException.class, () -> w.service.accept(r.id(), "anna"));
    }

    @Test
    @DisplayName("Remote and on-holiday volunteers are never asked")
    void onlyPeopleInTheOfficeAreAsked() {
        w.presence.setUsual("anna", Presence.holiday());
        w.presence.setUsual("bjorn", Presence.remote());
        w.usuallyAt(OSLO, "carl");

        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());

        assertEquals("carl", r.assignee());
        assertTrue(w.chat.directTo("anna").isEmpty());
        assertTrue(w.chat.directTo("bjorn").isEmpty());
    }

    @Test
    @DisplayName("Nobody in the office: open call, admins escalated once, and a person arriving later is asked")
    void openCallLifecycle() {
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        assertEquals(RefillState.OPEN, r.state());
        assertEquals(1, w.chat.channel("#snacks-oslo").size());

        w.advance(Duration.ofHours(1));
        w.service.tick();
        assertEquals(1, w.chat.channel("#snacks-oslo").size(), "open call is not re-posted on every tick");

        w.advance(Duration.ofHours(3));
        w.service.tick();
        w.service.tick();
        assertEquals(1, w.chat.channel("#snack-admins").size(), "admins pinged exactly once");

        w.presence.set("bjorn", w.today(OSLO), Presence.atOffice(OSLO));
        w.service.tick();
        assertEquals(RefillState.OFFERED, r.state());
        assertEquals("bjorn", r.assignee());
    }

    @Test
    @DisplayName("Receipts are only accepted from the committed buyer and in the office currency")
    void receiptValidation() {
        w.usuallyAt(OSLO, "anna", "bjorn");
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());

        assertThrows(RefillConflictException.class,
                () -> w.service.submitReceipt(r.id(), "anna", w.receipt(OSLO, "500")), "not yet accepted");

        w.service.accept(r.id(), "anna");
        assertThrows(RefillConflictException.class,
                () -> w.service.submitReceipt(r.id(), "bjorn", w.receipt(OSLO, "500")), "wrong person");
        assertThrows(IllegalArgumentException.class,
                () -> w.service.submitReceipt(r.id(), "anna", w.receipt(BENGALURU, "500")), "INR receipt for Oslo");
        assertEquals(RefillState.COMMITTED, r.state());
        assertTrue(w.hr.claims().isEmpty());
    }

    @Test
    @DisplayName("HR outage: claim is retried on the next tick and never duplicated")
    void hrOutageIsRetried() {
        w.usuallyAt(OSLO, "anna");
        w.hr.failNextSubmits(2);

        Refill r = w.buy(OSLO, "600");
        assertEquals(RefillState.PURCHASED, r.state());

        w.service.tick();
        assertEquals(RefillState.PURCHASED, r.state(), "second attempt also fails");

        w.service.tick();
        assertEquals(RefillState.CLAIM_SUBMITTED, r.state());
        w.service.tick();
        assertEquals(1, w.hr.claims().size());
        assertEquals(3, w.hr.submitCalls());
    }

    @Test
    @DisplayName("HR rejection is reported to the volunteer and admins")
    void hrRejection() {
        w.usuallyAt(OSLO, "anna");
        Refill r = w.buy(OSLO, "600");

        w.hr.reject(r.claimId());
        w.service.tick();

        assertEquals(RefillState.CLAIM_REJECTED, r.state());
        assertTrue(w.chat.lastDirectTo("anna").contains("rejected"));
        assertTrue(w.chat.channel("#snack-admins").getLast().contains("rejected"));
    }

    @Test
    @DisplayName("A claim pending at HR for more than 10 days is escalated once")
    void slowClaimEscalation() {
        w.usuallyAt(OSLO, "anna");
        Refill r = w.buy(OSLO, "600");

        w.advance(Duration.ofDays(9));
        w.service.tick();
        assertTrue(w.chat.channel("#snack-admins").isEmpty());

        w.advance(Duration.ofDays(1));
        w.service.tick();
        w.service.tick();
        assertEquals(1, w.chat.channel("#snack-admins").size());
        assertTrue(r.slowClaimEscalated());
    }

    @Test
    @DisplayName("A visitor from Oslo can refill Bengaluru: INR receipt, INR claim, Bengaluru budget")
    void visitorRefillsOtherOffice() {
        w.presence.setRange("carl", w.today(BENGALURU), w.today(BENGALURU).plusDays(6), Presence.atOffice(BENGALURU));

        Refill r = w.service.reportEmpty(BENGALURU, "priya", List.of("biscuits"));
        assertEquals("carl", r.assignee());
        w.service.accept(r.id(), "carl");
        w.service.submitReceipt(r.id(), "carl", w.receipt(BENGALURU, "2000"));

        FakeHrSystem.Claim claim = w.hr.claimForRefill(r.id()).orElseThrow();
        assertEquals("carl", claim.request().employeeId());
        assertEquals(Money.of(2000, Money.INR), claim.request().amount(), "claimed in receipt currency; HR pays NOK");
        assertTrue(w.chat.lastDirectTo("carl").contains("paid in NOK"));

        var month = java.time.YearMonth.of(2026, 11);
        assertEquals(Money.of(13000, Money.INR), w.service.remainingBudget(BENGALURU, month));
        assertEquals(Money.of(6000, Money.NOK), w.service.remainingBudget(OSLO, month));
    }

    @Test
    @DisplayName("A person busy with one refill is not offered another")
    void onePersonOneActiveRefill() {
        w.usuallyAt(OSLO, "anna");
        w.presence.set("anna", w.today(OSLO), Presence.atOffice(OSLO));
        Refill oslo = w.service.reportEmpty(OSLO, "kari", List.of());
        w.service.accept(oslo.id(), "anna");

        // Anna is (unrealistically) also listed in Bengaluru today; she must not be asked twice.
        w.presence.set("anna", w.today(BENGALURU), Presence.atOffice(BENGALURU));
        Refill blr = w.service.reportEmpty(BENGALURU, "priya", List.of());
        assertEquals(RefillState.OPEN, blr.state());
    }

    @Test
    @DisplayName("Every transition is recorded in the audit history; illegal transitions are impossible")
    void historyAndIllegalTransitions() {
        w.usuallyAt(OSLO, "anna");
        Refill r = w.buy(OSLO, "500");

        assertTrue(r.history().size() >= 4);
        assertTrue(r.history().getFirst().contains("reported empty"));
        assertThrows(IllegalStateException.class, () -> r.moveTo(RefillState.COMMITTED, w.clock.instant(), null));
    }
}
