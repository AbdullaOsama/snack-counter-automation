package snackcounter.domain;

import java.time.ZoneId;
import java.util.Currency;

/**
 * Per-office settings. Kept as data (not hard-coded in the logic) so a third office
 * or a new budget is a config change, not a code change.
 *
 * @param channel      public chat channel where the office's volunteers see refill status
 * @param adminChannel chat channel for whoever owns the snack budget (escalations, approvals)
 */
public record OfficeConfig(
        OfficeId id,
        String name,
        Currency currency,
        ZoneId zone,
        Money monthlyBudget,
        Money typicalMin,
        Money typicalMax,
        String channel,
        String adminChannel) {

    public static OfficeConfig oslo() {
        return new OfficeConfig(OfficeId.OSLO, "Oslo", Money.NOK, ZoneId.of("Europe/Oslo"),
                Money.of(6000, Money.NOK), Money.of(300, Money.NOK), Money.of(1200, Money.NOK),
                "#snacks-oslo", "#snack-admins");
    }

    public static OfficeConfig bengaluru() {
        return new OfficeConfig(OfficeId.BENGALURU, "Bengaluru", Money.INR, ZoneId.of("Asia/Kolkata"),
                Money.of(15000, Money.INR), Money.of(500, Money.INR), Money.of(3000, Money.INR),
                "#snacks-bengaluru", "#snack-admins");
    }
}
