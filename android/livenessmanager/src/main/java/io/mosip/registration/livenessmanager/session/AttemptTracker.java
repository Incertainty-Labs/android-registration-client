package io.mosip.registration.livenessmanager.session;

import java.util.List;

import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.ChallengeProgress;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;

/**
 * Counts the liveness attempts of one workflow and field (API.md §3.7, same signature, so it can move to
 * liveness-core unchanged). An attempt is used only by an error that counts as one (API.md §6): a pass, a
 * cancel or a device error never uses one. Thread-safe.
 */
public final class AttemptTracker {

    private final int limit;
    private int used;

    /**
     * @param policy           gives {@code max.retries}
     * @param clientRetryLimit the client's own limit ({@code mosip.registration.num_of_face_retries}); 0 or less
     *                         when it has none
     */
    public AttemptTracker(LivenessPolicy policy, int clientRetryLimit) {
        this.limit = clientRetryLimit > 0 ? Math.min(policy.maxRetries(), clientRetryLimit) : policy.maxRetries();
    }

    /** @return the attempt in progress or next, 1-based */
    public synchronized int current() {
        return used + 1;
    }

    /** @return attempts allowed */
    public int limit() {
        return limit;
    }

    /**
     * Records the outcome of the current attempt.
     *
     * @param result the final result
     * @return true if another attempt is allowed
     */
    public synchronized boolean record(LivenessResult result) {
        if (result.errorCode() != null && result.errorCode().countsAsAttempt(endedInChallenge(result), true)) {
            used++;
        }
        return used < limit;
    }

    /** Starts again at attempt 1, for example for a new registration. */
    public synchronized void reset() {
        used = 0;
    }

    private static boolean endedInChallenge(LivenessResult result) {
        List<ChallengeOutcome> challenges = result.challenges();
        return !challenges.isEmpty()
                && challenges.get(challenges.size() - 1).outcome() != ChallengeProgress.PASSED;
    }
}
