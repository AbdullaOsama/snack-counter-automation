package snackcounter.core;

import snackcounter.domain.Money;
import snackcounter.domain.OfficeConfig;

import java.time.YearMonth;
import java.util.Collection;

/**
 * Budget arithmetic, always in the office's own currency. Spend is attributed to the office where the
 * snacks were bought and the month of the receipt date, regardless of who bought them.
 * There is no separate ledger table: spend is derived from the refills, so it can never drift.
 */
final class Budget {

    private Budget() {
    }

    static Money spent(OfficeConfig office, YearMonth month, Collection<Refill> refills) {
        Money total = Money.zero(office.currency());
        for (Refill r : refills) {
            if (r.office() == office.id()
                    && r.state().countsAgainstBudget()
                    && YearMonth.from(r.receipt().purchaseDate()).equals(month)) {
                total = total.plus(r.receipt().total());
            }
        }
        return total;
    }

    static Money remaining(OfficeConfig office, YearMonth month, Collection<Refill> refills) {
        return office.monthlyBudget().minus(spent(office, month, refills));
    }

    /** What we tell the buyer they may spend: the typical max, or less if the month is nearly used up. */
    static Money spendingLimit(OfficeConfig office, YearMonth month, Collection<Refill> refills) {
        return remaining(office, month, refills).min(office.typicalMax());
    }

    /** Below the typical minimum purchase a refill is not worth starting: hold it for admins / next month. */
    static boolean enoughForARefill(OfficeConfig office, Money limit) {
        return !limit.isLessThan(office.typicalMin());
    }
}
