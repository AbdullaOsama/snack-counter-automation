package snackcounter.core;

import snackcounter.domain.Volunteer;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses who to ask. Pure function (no I/O), so the fairness rule is trivial to test and explain.
 *
 * <p>Rule, in order:
 * <ol>
 *   <li>fewest refills in the fairness window (spreads the work)</li>
 *   <li>not currently waiting for a reimbursement (nobody carries two debts at once)</li>
 *   <li>longest since their last refill; never-refilled first</li>
 *   <li>employee id, so the result is deterministic</li>
 * </ol>
 * Eligibility (in the office today, not excluded, not busy) is decided by the caller.
 */
public final class VolunteerPicker {

    public record Candidate(Volunteer volunteer, int recentRefills, boolean awaitingReimbursement,
                            Instant lastRefillAt) {
    }

    static final Comparator<Candidate> FAIRNESS = Comparator
            .comparingInt(Candidate::recentRefills)
            .thenComparing(Candidate::awaitingReimbursement)
            .thenComparing(Candidate::lastRefillAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing((Candidate c) -> c.volunteer().employeeId());

    public Optional<Volunteer> pick(List<Candidate> candidates) {
        return candidates.stream().min(FAIRNESS).map(Candidate::volunteer);
    }
}
