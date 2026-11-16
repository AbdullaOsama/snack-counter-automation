package snackcounter.core;

import snackcounter.domain.Money;
import snackcounter.domain.OfficeId;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one report management asked for. Per office everything is in local currency (exact);
 * only the grand total is converted, at a stated rate, so nobody mistakes it for an exact figure.
 */
public record MonthlyReport(
        YearMonth month,
        Currency reportingCurrency,
        List<OfficeLine> offices,
        Money totalSpent,
        Map<String, Integer> refillsByVolunteer,
        Map<String, Integer> ranOutMostOften) {

    public record OfficeLine(
            OfficeId office,
            Money budget,
            Money spent,
            Money remaining,
            BigDecimal fxRate,
            Money spentInReportingCurrency,
            int refills,
            Money reimbursed,
            Money awaitingReimbursement,
            Duration averageEmptyToRefilled) {
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Snack spending report ").append(month).append('\n');
        for (OfficeLine l : offices) {
            sb.append(String.format(Locale.ROOT, "  %-10s spent %-14s of %-14s (left %s), %d refills, avg empty->refilled %s%n",
                    l.office(), l.spent(), l.budget(), l.remaining(), l.refills(), hours(l.averageEmptyToRefilled())));
            sb.append(String.format(Locale.ROOT, "  %-10s reimbursed %s, still owed to volunteers %s, = %s at rate %s%n",
                    "", l.reimbursed(), l.awaitingReimbursement(), l.spentInReportingCurrency(), l.fxRate().stripTrailingZeros().toPlainString()));
        }
        sb.append("  TOTAL      ").append(totalSpent).append('\n');
        sb.append("  Refills per volunteer: ").append(refillsByVolunteer).append('\n');
        sb.append("  Ran out most often:    ").append(ranOutMostOften).append('\n');
        return sb.toString();
    }

    private static String hours(Duration d) {
        return d == null ? "n/a" : String.format(Locale.ROOT, "%.1fh", d.toMinutes() / 60.0);
    }
}
