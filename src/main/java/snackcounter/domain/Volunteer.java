package snackcounter.domain;

import java.util.Currency;

/**
 * A person who has opted in to refilling. The payroll currency is informational:
 * HR pays the claim in this currency, we always claim in the receipt's currency.
 */
public record Volunteer(String employeeId, String name, OfficeId homeOffice, Currency payrollCurrency) {
}
