package snackcounter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import snackcounter.domain.Money;
import snackcounter.fakes.FixedFxRates;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static snackcounter.domain.OfficeId.BENGALURU;
import static snackcounter.domain.OfficeId.OSLO;

class MonthlyReportTest {

    private static final YearMonth NOV = YearMonth.of(2026, 11);

    private final TestWorld w = new TestWorld().usuallyAt(OSLO, "anna").usuallyAt(BENGALURU, "ravi");
    private final FixedFxRates fx = new FixedFxRates().with(Money.INR, Money.NOK, "0.12");
    private final MonthlyReportService reports = new MonthlyReportService(w.service, fx);

    @Test
    @DisplayName("Totals are exact per office and correctly combined across NOK and INR")
    void totalsAcrossCurrencies() {
        Refill first = w.buy(OSLO, "1000");
        w.buy(OSLO, "500");
        w.buy(BENGALURU, "2000");
        w.hr.approve(first.claimId());
        w.service.tick();

        MonthlyReport report = reports.build(NOV, Money.NOK);

        MonthlyReport.OfficeLine oslo = report.offices().get(0);
        assertEquals(OSLO, oslo.office());
        assertEquals(Money.of(1500, Money.NOK), oslo.spent());
        assertEquals(Money.of(4500, Money.NOK), oslo.remaining());
        assertEquals(Money.of(1000, Money.NOK), oslo.reimbursed());
        assertEquals(Money.of(500, Money.NOK), oslo.awaitingReimbursement());
        assertEquals(2, oslo.refills());

        MonthlyReport.OfficeLine blr = report.offices().get(1);
        assertEquals(Money.of(2000, Money.INR), blr.spent());
        assertEquals(Money.of(13000, Money.INR), blr.remaining());
        assertEquals(Money.of(240, Money.NOK), blr.spentInReportingCurrency());

        assertEquals(Money.of(1740, Money.NOK), report.totalSpent());
        assertEquals(Map.of("Anna", 2, "Ravi", 1), report.refillsByVolunteer());
        assertEquals(Map.of("chips", 3), report.ranOutMostOften());
    }

    @Test
    @DisplayName("Report can be produced in INR too; conversion uses the inverse rate")
    void reportInInr() {
        w.buy(OSLO, "120");
        w.buy(BENGALURU, "1000");

        MonthlyReport report = reports.build(NOV, Money.INR);

        assertEquals(Money.of(2000, Money.INR), report.totalSpent()); // 120 NOK / 0.12 = 1000 INR, + 1000 INR
    }

    @Test
    @DisplayName("Other months and rejected claims are excluded")
    void excludesOtherMonthsAndRejected() {
        w.buy(OSLO, "400");
        Refill rejected = w.buy(OSLO, "900");
        w.hr.reject(rejected.claimId());
        w.service.tick();

        w.clock.set(Instant.parse("2026-12-03T08:00:00Z"));
        w.buy(OSLO, "1100");

        MonthlyReport nov = reports.build(NOV, Money.NOK);
        assertEquals(Money.of(400, Money.NOK), nov.totalSpent());
        assertEquals(1, nov.offices().get(0).refills());
    }

    @Test
    @DisplayName("Report shows how long the box stayed empty, and renders as text for chat/email")
    void timeToRefillAndRender() {
        var r = w.service.reportEmpty(OSLO, "kari", java.util.List.of("nuts"));
        w.advance(Duration.ofHours(3));
        w.service.accept(r.id(), "anna");
        w.service.submitReceipt(r.id(), "anna", w.receipt(OSLO, "700"));

        MonthlyReport report = reports.build(NOV, Money.NOK);

        assertEquals(Duration.ofHours(3), report.offices().get(0).averageEmptyToRefilled());
        String text = report.render();
        assertTrue(text.contains("TOTAL      700.00 NOK"), text);
        assertTrue(text.contains("3.0h"), text);
    }
}
