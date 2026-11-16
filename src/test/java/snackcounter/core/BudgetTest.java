package snackcounter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import snackcounter.domain.Money;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static snackcounter.domain.OfficeId.BENGALURU;
import static snackcounter.domain.OfficeId.OSLO;

class BudgetTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);
    private static final YearMonth DEC = YearMonth.of(2026, 12);

    private final TestWorld w = new TestWorld().usuallyAt(OSLO, "anna", "bjorn").usuallyAt(BENGALURU, "asha");

    @Test
    @DisplayName("Spending limit is the typical max, shrinking to what is left near the end of the month")
    void spendingLimit() {
        Refill first = w.service.reportEmpty(OSLO, "kari", List.of());
        assertEquals(Money.of(1200, Money.NOK), first.spendingLimit());
        w.service.accept(first.id(), first.assignee());
        w.service.submitReceipt(first.id(), first.assignee(), w.receipt(OSLO, "1200"));

        w.buy(OSLO, "1200");
        w.buy(OSLO, "1200");
        w.buy(OSLO, "1200");
        w.buy(OSLO, "500");
        assertEquals(Money.of(700, Money.NOK), w.service.remainingBudget(OSLO, NOV));

        Refill next = w.service.reportEmpty(OSLO, "kari", List.of());
        assertEquals(Money.of(700, Money.NOK), next.spendingLimit());
        assertTrue(w.chat.lastDirectTo(next.assignee()).contains("700.00 NOK"));
    }

    @Test
    @DisplayName("A receipt over the limit waits for admin approval before any HR claim; it still counts as spent")
    void overLimitNeedsApproval() {
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        w.service.accept(r.id(), r.assignee());
        w.service.submitReceipt(r.id(), r.assignee(), w.receipt(OSLO, "1500"));

        assertEquals(RefillState.NEEDS_APPROVAL, r.state());
        assertTrue(w.hr.claims().isEmpty());
        assertTrue(w.chat.channel("#snack-admins").getLast().contains("Approval needed"));
        assertEquals(Money.of(4500, Money.NOK), w.service.remainingBudget(OSLO, NOV));

        w.service.approveOverspend(r.id(), "office-manager");
        assertEquals(RefillState.CLAIM_SUBMITTED, r.state());
        assertEquals(Money.of(1500, Money.NOK), w.hr.claimForRefill(r.id()).orElseThrow().request().amount());
    }

    @Test
    @DisplayName("When less than a typical purchase is left, the refill is put on hold and nobody is asked to buy")
    void exhaustedBudgetPutsRefillOnHold() {
        for (int i = 0; i < 4; i++) {
            w.buy(OSLO, "1200");
        }
        w.buy(OSLO, "1000"); // 5 800 spent, 200 left < 300 minimum
        int directMessagesBefore = w.chat.all().size();

        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());

        assertEquals(RefillState.ON_HOLD_BUDGET, r.state());
        assertTrue(w.chat.channel("#snack-admins").getLast().contains("200.00 NOK"));
        assertEquals(directMessagesBefore + 1, w.chat.all().size(), "only the admin message, no offer");
    }

    @Test
    @DisplayName("A refill on hold is offered automatically when the new month's budget starts")
    void newMonthReleasesHold() {
        for (int i = 0; i < 5; i++) {
            w.buy(OSLO, "1200");
        }
        Refill r = w.service.reportEmpty(OSLO, "kari", List.of());
        assertEquals(RefillState.ON_HOLD_BUDGET, r.state());

        w.service.tick();
        assertEquals(RefillState.ON_HOLD_BUDGET, r.state());

        w.clock.set(Instant.parse("2026-12-01T08:00:00Z"));
        w.service.tick();

        assertEquals(RefillState.OFFERED, r.state());
        assertEquals(Money.of(1200, Money.NOK), r.spendingLimit());
        assertEquals(Money.of(6000, Money.NOK), w.service.remainingBudget(OSLO, DEC));
        assertEquals(Money.zero(Money.NOK), w.service.remainingBudget(OSLO, NOV));
    }

    @Test
    @DisplayName("Each office's budget is tracked separately in its own currency")
    void budgetsAreSeparatePerOfficeAndCurrency() {
        w.buy(OSLO, "800");
        w.buy(BENGALURU, "2500");
        w.buy(BENGALURU, "1500.50");

        assertEquals(Money.of(800, Money.NOK), w.service.spent(OSLO, NOV));
        assertEquals(Money.of(5200, Money.NOK), w.service.remainingBudget(OSLO, NOV));
        assertEquals(Money.of("4000.50", Money.INR), w.service.spent(BENGALURU, NOV));
        assertEquals(Money.of("10999.50", Money.INR), w.service.remainingBudget(BENGALURU, NOV));
    }

    @Test
    @DisplayName("A claim HR rejects no longer counts against the budget")
    void rejectedClaimsDoNotCount() {
        Refill r = w.buy(OSLO, "1000");
        assertEquals(Money.of(5000, Money.NOK), w.service.remainingBudget(OSLO, NOV));

        w.hr.reject(r.claimId());
        w.service.tick();

        assertEquals(Money.of(6000, Money.NOK), w.service.remainingBudget(OSLO, NOV));
    }

    @Test
    @DisplayName("Spending is counted in the month of the receipt, so budgets reset monthly")
    void monthOfReceiptDecides() {
        w.buy(OSLO, "1000");
        w.clock.set(Instant.parse("2026-12-02T08:00:00Z"));
        w.buy(OSLO, "700");

        assertEquals(Money.of(1000, Money.NOK), w.service.spent(OSLO, NOV));
        assertEquals(Money.of(700, Money.NOK), w.service.spent(OSLO, DEC));
    }
}
