package snackcounter.fakes;

import snackcounter.domain.Presence;
import snackcounter.ports.PresenceCalendar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Simulated presence source: a "usual" presence per person plus per-day overrides (holiday, visit, WFH). */
public final class InMemoryPresenceCalendar implements PresenceCalendar {

    private final Map<String, Presence> usual = new HashMap<>();
    private final Map<String, Map<LocalDate, Presence>> overrides = new HashMap<>();

    public InMemoryPresenceCalendar setUsual(String employeeId, Presence presence) {
        usual.put(employeeId, presence);
        return this;
    }

    public InMemoryPresenceCalendar set(String employeeId, LocalDate date, Presence presence) {
        overrides.computeIfAbsent(employeeId, k -> new HashMap<>()).put(date, presence);
        return this;
    }

    public InMemoryPresenceCalendar setRange(String employeeId, LocalDate from, LocalDate toInclusive, Presence presence) {
        for (LocalDate d = from; !d.isAfter(toInclusive); d = d.plusDays(1)) {
            set(employeeId, d, presence);
        }
        return this;
    }

    @Override
    public Presence on(String employeeId, LocalDate date) {
        Presence override = overrides.getOrDefault(employeeId, Map.of()).get(date);
        if (override != null) {
            return override;
        }
        return usual.getOrDefault(employeeId, Presence.remote());
    }
}
