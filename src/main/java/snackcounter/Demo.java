package snackcounter;

import snackcounter.core.MonthlyReportService;
import snackcounter.core.Policy;
import snackcounter.core.Refill;
import snackcounter.core.RefillConflictException;
import snackcounter.core.RefillService;
import snackcounter.domain.Money;
import snackcounter.domain.OfficeConfig;
import snackcounter.domain.OfficeId;
import snackcounter.domain.Presence;
import snackcounter.domain.Receipt;
import snackcounter.domain.Volunteer;
import snackcounter.fakes.FakeChat;
import snackcounter.fakes.FakeHrSystem;
import snackcounter.fakes.FixedFxRates;
import snackcounter.fakes.InMemoryPresenceCalendar;
import snackcounter.fakes.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * A narrated walk through one day in both offices, using the simulated chat and HR system.
 * Run with:  mvn -q compile exec:java
 */
public final class Demo {

    public static void main(String[] args) {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-16T08:00:00Z"), ZoneOffset.UTC);
        FakeChat chat = new FakeChat(true);
        FakeHrSystem hr = new FakeHrSystem();
        InMemoryPresenceCalendar presence = new InMemoryPresenceCalendar();

        List<Volunteer> volunteers = List.of(
                new Volunteer("anna", "Anna", OfficeId.OSLO, Money.NOK),
                new Volunteer("bjorn", "Bjorn", OfficeId.OSLO, Money.NOK),
                new Volunteer("carl", "Carl", OfficeId.OSLO, Money.NOK),
                new Volunteer("asha", "Asha", OfficeId.BENGALURU, Money.INR),
                new Volunteer("ravi", "Ravi", OfficeId.BENGALURU, Money.INR));

        presence.setUsual("anna", Presence.atOffice(OfficeId.OSLO))
                .setUsual("bjorn", Presence.atOffice(OfficeId.OSLO))
                .setUsual("carl", Presence.remote())
                .setUsual("asha", Presence.holiday())
                .setUsual("ravi", Presence.remote())
                // Carl is visiting Bengaluru this week
                .setRange("carl", LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 20), Presence.atOffice(OfficeId.BENGALURU));

        RefillService service = new RefillService(List.of(OfficeConfig.oslo(), OfficeConfig.bengaluru()),
                volunteers, presence, chat, hr, clock, Policy.defaults());

        step("09:00 Oslo: someone notices the box is empty (and a colleague reports it again)");
        Refill oslo = service.reportEmpty(OfficeId.OSLO, "kari", List.of("chocolate", "nuts"));
        service.reportEmpty(OfficeId.OSLO, "ola", List.of("chocolate"));

        step("Anna (first in the rotation) does not answer for 2 hours, so the system moves on");
        clock.advance(Duration.ofHours(2));
        service.tick();

        step("Bjorn accepts. Anna then tries to accept too and is refused");
        service.accept(oslo.id(), "bjorn");
        try {
            service.accept(oslo.id(), "anna");
        } catch (RefillConflictException e) {
            System.out.println("  refused: " + e.getMessage());
        }

        step("Bjorn uploads an 850 NOK receipt; the HR claim is filed automatically");
        service.submitReceipt(oslo.id(), "bjorn", receipt("850", Money.NOK, "2026-11-16", "chocolate", "nuts", "fruit"));

        step("Bengaluru box empty. Asha is on holiday and Ravi is at home, but Carl is visiting from Oslo");
        Refill blr = service.reportEmpty(OfficeId.BENGALURU, "priya", List.of("biscuits"));
        service.accept(blr.id(), "carl");
        service.submitReceipt(blr.id(), "carl", receipt("2400", Money.INR, "2026-11-16", "biscuits", "chips"));

        step("Three days later HR approves both claims");
        clock.advance(Duration.ofDays(3));
        hr.claims().forEach(c -> hr.approve(c.id()));
        service.tick();

        step("Monthly report for management");
        FixedFxRates fx = new FixedFxRates().with(Money.INR, Money.NOK, "0.125");
        System.out.println(new MonthlyReportService(service, fx).build(YearMonth.of(2026, 11), Money.NOK).render());

        step("Audit trail of the Oslo refill");
        oslo.history().forEach(h -> System.out.println("  " + h));
    }

    private static Receipt receipt(String amount, java.util.Currency currency, String date, String... items) {
        return new Receipt(Money.of(amount, currency), LocalDate.parse(date), List.of(items), new byte[]{1});
    }

    private static void step(String text) {
        System.out.println();
        System.out.println("== " + text);
    }
}
