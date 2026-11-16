package snackcounter.core;

import snackcounter.domain.Money;
import snackcounter.domain.OfficeConfig;
import snackcounter.domain.OfficeId;
import snackcounter.domain.Presence;
import snackcounter.domain.Receipt;
import snackcounter.domain.Volunteer;
import snackcounter.fakes.FakeChat;
import snackcounter.fakes.FakeHrSystem;
import snackcounter.fakes.InMemoryPresenceCalendar;
import snackcounter.fakes.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Shared test setup: two offices, five volunteers, everybody working from home unless a test says otherwise.
 * Start time is Monday 16 Nov 2026, 09:00 in Oslo / 13:30 in Bengaluru.
 */
final class TestWorld {

    static final Instant START = Instant.parse("2026-11-16T08:00:00Z");

    static final Volunteer ANNA = new Volunteer("anna", "Anna", OfficeId.OSLO, Money.NOK);
    static final Volunteer BJORN = new Volunteer("bjorn", "Bjorn", OfficeId.OSLO, Money.NOK);
    static final Volunteer CARL = new Volunteer("carl", "Carl", OfficeId.OSLO, Money.NOK);
    static final Volunteer ASHA = new Volunteer("asha", "Asha", OfficeId.BENGALURU, Money.INR);
    static final Volunteer RAVI = new Volunteer("ravi", "Ravi", OfficeId.BENGALURU, Money.INR);

    final MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
    final FakeChat chat = new FakeChat();
    final FakeHrSystem hr = new FakeHrSystem();
    final InMemoryPresenceCalendar presence = new InMemoryPresenceCalendar();
    final OfficeConfig oslo = OfficeConfig.oslo();
    final OfficeConfig bengaluru = OfficeConfig.bengaluru();
    final RefillService service;

    TestWorld() {
        service = new RefillService(List.of(oslo, bengaluru), List.of(ANNA, BJORN, CARL, ASHA, RAVI),
                presence, chat, hr, clock, Policy.defaults());
    }

    /** These people are in the given office every day (until overridden). */
    TestWorld usuallyAt(OfficeId office, String... employeeIds) {
        for (String id : employeeIds) {
            presence.setUsual(id, Presence.atOffice(office));
        }
        return this;
    }

    void advance(Duration d) {
        clock.advance(d);
    }

    LocalDate today(OfficeId office) {
        OfficeConfig cfg = office == OfficeId.OSLO ? oslo : bengaluru;
        return LocalDate.ofInstant(clock.instant(), cfg.zone());
    }

    Receipt receipt(OfficeId office, String amount) {
        Money total = Money.of(amount, office == OfficeId.OSLO ? Money.NOK : Money.INR);
        return new Receipt(total, today(office), List.of("chips"), new byte[]{42});
    }

    /** Report empty, let whoever is offered accept, and upload a receipt. Returns the refill. */
    Refill buy(OfficeId office, String amount) {
        Refill r = service.reportEmpty(office, "reporter", List.of("chips"));
        String buyer = r.assignee();
        if (buyer == null) {
            throw new IllegalStateException("Nobody was offered " + r + "; set presence first");
        }
        service.accept(r.id(), buyer);
        service.submitReceipt(r.id(), buyer, receipt(office, amount));
        return r;
    }
}
