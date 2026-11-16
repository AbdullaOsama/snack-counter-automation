package snackcounter.ports;

import snackcounter.domain.Money;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Currency;

/**
 * Exchange rates, used ONLY for the cross-office management report.
 * Budgets are always checked in the office's own currency, so FX never affects who may buy what.
 */
public interface FxRates {

    BigDecimal rate(Currency from, Currency to, YearMonth month);

    default Money convert(Money money, Currency to, YearMonth month) {
        if (money.currency().equals(to)) {
            return money;
        }
        return new Money(money.amount().multiply(rate(money.currency(), to, month)), to);
    }
}
