package snackcounter.fakes;

import snackcounter.ports.ClaimRequest;
import snackcounter.ports.ClaimStatus;
import snackcounter.ports.HrSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simulated external HR system. Approval is manual ({@link #approve}) so tests control the 1-10 day delay,
 * and {@link #failNextSubmits} simulates an outage.
 */
public final class FakeHrSystem implements HrSystem {

    public record Claim(String id, ClaimRequest request, ClaimStatus status) {
    }

    private final Map<String, Claim> claims = new LinkedHashMap<>();
    private final Map<String, String> claimIdByIdempotencyKey = new HashMap<>();
    private int failuresToSimulate;
    private int submitCalls;

    @Override
    public synchronized String submitClaim(ClaimRequest request) {
        submitCalls++;
        if (failuresToSimulate > 0) {
            failuresToSimulate--;
            throw new IllegalStateException("HR system unavailable (simulated)");
        }
        String existing = claimIdByIdempotencyKey.get(request.idempotencyKey());
        if (existing != null) {
            return existing;
        }
        String id = "HR-" + (claims.size() + 1000);
        claims.put(id, new Claim(id, request, ClaimStatus.PENDING));
        claimIdByIdempotencyKey.put(request.idempotencyKey(), id);
        return id;
    }

    @Override
    public synchronized ClaimStatus status(String claimId) {
        return claim(claimId).status();
    }

    public synchronized void approve(String claimId) {
        setStatus(claimId, ClaimStatus.APPROVED);
    }

    public synchronized void reject(String claimId) {
        setStatus(claimId, ClaimStatus.REJECTED);
    }

    public synchronized void failNextSubmits(int count) {
        failuresToSimulate = count;
    }

    public synchronized int submitCalls() {
        return submitCalls;
    }

    public synchronized List<Claim> claims() {
        return new ArrayList<>(claims.values());
    }

    public synchronized Optional<Claim> claimForRefill(String refillId) {
        return Optional.ofNullable(claimIdByIdempotencyKey.get(refillId)).map(claims::get);
    }

    private void setStatus(String claimId, ClaimStatus status) {
        Claim c = claim(claimId);
        claims.put(claimId, new Claim(c.id(), c.request(), status));
    }

    private Claim claim(String claimId) {
        Claim c = claims.get(claimId);
        if (c == null) {
            throw new IllegalArgumentException("Unknown claim " + claimId);
        }
        return c;
    }
}
