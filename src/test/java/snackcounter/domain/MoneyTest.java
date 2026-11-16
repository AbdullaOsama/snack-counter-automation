package snackcounter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    @DisplayName("NOK and INR can never be added or compared by accident")
    void currenciesNeverMix() {
        Money nok = Money.of(100, Money.NOK);
        Money inr = Money.of(100, Money.INR);
        assertThrows(IllegalArgumentException.class, () -> nok.plus(inr));
        assertThrows(IllegalArgumentException.class, () -> nok.minus(inr));
        assertThrows(IllegalArgumentException.class, () -> nok.isGreaterThan(inr));
    }

    @Test
    @DisplayName("Exact decimal arithmetic, no floating-point drift")
    void exactArithmetic() {
        Money total = Money.zero(Money.NOK);
        for (int i = 0; i < 10; i++) {
            total = total.plus(Money.of("0.10", Money.NOK));
        }
        assertEquals(Money.of(1, Money.NOK), total);
        assertEquals(Money.of("1.00", Money.NOK), Money.of("1", Money.NOK), "scale is normalised");
    }

    @Test
    void comparisons() {
        Money a = Money.of(300, Money.NOK);
        Money b = Money.of(1200, Money.NOK);
        assertEquals(a, a.min(b));
        assertTrue(b.isGreaterThan(a));
        assertTrue(a.isLessThan(b));
        assertTrue(a.isPositive());
        assertEquals("300.00 NOK", a.toString());
    }
}
