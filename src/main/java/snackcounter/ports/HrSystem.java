package snackcounter.ports;

/**
 * The external HR system (run by another company). We can create claims and read their status.
 * We assume it accepts an idempotency key so a retried submit never creates a second claim;
 * if it does not, we would store "submit in progress" before calling and reconcile by description.
 */
public interface HrSystem {

    /** @return the HR system's claim id */
    String submitClaim(ClaimRequest request);

    ClaimStatus status(String claimId);
}
