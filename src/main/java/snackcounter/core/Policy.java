package snackcounter.core;

import java.time.Duration;

/**
 * Tunable timings. Defaults are guesses to validate with the volunteers.
 *
 * @param offerTimeout        how long a volunteer has to accept/decline an offer before it moves on
 * @param purchaseTimeout     how long after accepting they have to upload a receipt before it is reassigned
 * @param openCallEscalation  how long an open call may go unanswered before admins are pinged
 * @param slowClaimThreshold  HR promises 1-10 days; a claim pending longer is escalated
 * @param fairnessWindow      how far back "who refilled recently" looks
 */
public record Policy(Duration offerTimeout, Duration purchaseTimeout, Duration openCallEscalation,
                     Duration slowClaimThreshold, Duration fairnessWindow) {

    public static Policy defaults() {
        return new Policy(Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(4),
                Duration.ofDays(10), Duration.ofDays(90));
    }
}
