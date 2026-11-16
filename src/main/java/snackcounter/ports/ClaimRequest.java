package snackcounter.ports;

import snackcounter.domain.Money;

/**
 * Exactly the fields the HR system needs: amount, currency (inside Money), description,
 * receipt image and employee id, plus our refill id as the idempotency key.
 */
public record ClaimRequest(String idempotencyKey, String employeeId, Money amount,
                           String description, byte[] receiptImage) {
}
