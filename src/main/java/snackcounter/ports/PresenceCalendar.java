package snackcounter.ports;

import snackcounter.domain.Presence;

import java.time.LocalDate;

/**
 * Who is where today. In production: the office desk-booking / calendar system, or a simple
 * "I'm in today" check-in in chat. This is how hybrid work and office visits are handled.
 */
public interface PresenceCalendar {

    Presence on(String employeeId, LocalDate date);
}
