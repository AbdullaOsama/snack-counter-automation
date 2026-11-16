package snackcounter.fakes;

import snackcounter.ports.FxRates;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.YearMonth;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

/** One fixed rate per currency pair (the inverse is derived). Real version: a monthly rate from finance. */
public final class FixedFxRates implements FxRates {

    private final Map<String, BigDecimal> rates = new HashMap<>();

    public FixedFxRates with(Currency from, Currency to, String rate) {
        BigDecimal r = new BigDecimal(rate);
        rates.put(key(from, to), r);
        rates.put(key(to, from), BigDecimal.ONE.divide(r, MathContext.DECIMAL64));
        return this;
    }

    @Override
    public BigDecimal rate(Currency from, Currency to, YearMonth month) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        BigDecimal r = rates.get(key(from, to));
        if (r == null) {
            throw new IllegalArgumentException("No FX rate " + key(from, to));
        }
        return r;
    }

    private static String key(Currency from, Currency to) {
        return from.getCurrencyCode() + "->" + to.getCurrencyCode();
    }
}
