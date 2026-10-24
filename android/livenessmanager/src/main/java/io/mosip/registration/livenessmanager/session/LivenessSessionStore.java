package io.mosip.registration.livenessmanager.session;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.engine.AttemptTracker;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;

/**
 * Liveness state that outlives one {@code LivenessActivity}: an {@link AttemptTracker} and the last full result
 * per workflow and field (API.md §10). The full result stays native, for packet evidence; only its
 * {@link LivenessResultSummary} goes to Flutter. One per process ({@link #shared()}). Thread-safe.
 */
public final class LivenessSessionStore {

    private static final LivenessSessionStore SHARED = new LivenessSessionStore();

    private final Map<String, AttemptTracker> trackers = new HashMap<>();
    private final Map<String, LivenessResult> results = new HashMap<>();
    /** The policy each field last began under, for the packet evidence (LIVENESS_THRESHOLD, LIVENESS_MODE). */
    private final Map<String, LivenessPolicy> policies = new HashMap<>();
    /** The result and policy whose pass let the field's last face capture through, for the packet evidence. */
    private final Map<String, LivenessResult> capturedResults = new HashMap<>();
    private final Map<String, LivenessPolicy> capturedPolicies = new HashMap<>();
    /** When each field's last check passed, until a capture uses the pass. */
    private final Map<String, Long> passedAtMs = new HashMap<>();
    private final LongSupplier clockMs;

    /**
     * A store on a monotonic clock, so changing the device's date and time can neither keep a pass fresh nor expire
     * it early; the app uses {@link #shared()}.
     */
    public LivenessSessionStore() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    LivenessSessionStore(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /** @return the store shared by the activity and the Pigeon API */
    public static LivenessSessionStore shared() {
        return SHARED;
    }

    /**
     * Starts an attempt for a field. The attempt count carries on from earlier attempts until {@link #reset}.
     *
     * @param workflow         the {@code Workflow} name
     * @param fieldId          the biometric field id, or {@code operatorAuth}
     * @param policy           the workflow's policy; used only when the field has no tracker yet
     * @param clientRetryLimit the client's own limit, 0 or less for none
     * @return the attempt about to run, 1-based
     */
    public synchronized int begin(String workflow, String fieldId, LivenessPolicy policy, int clientRetryLimit) {
        String key = key(workflow, fieldId);
        AttemptTracker tracker = trackers.get(key);
        if (tracker == null) {
            tracker = new AttemptTracker(policy, clientRetryLimit);
            trackers.put(key, tracker);
        }
        if (policy != null) {
            policies.put(key, policy);
        }
        return tracker.current();
    }

    /**
     * Records the final result of the attempt started by {@link #begin} and keeps it.
     *
     * @param workflow           the {@code Workflow} name
     * @param fieldId            the field
     * @param result             the final result
     * @param diagnosticsEnabled whether {@code diagnostics.enabled} is on
     * @return what the UI may show
     * @throws IllegalStateException if no attempt was begun for the field
     */
    public synchronized LivenessResultSummary complete(String workflow, String fieldId, LivenessResult result,
                                                       boolean diagnosticsEnabled) {
        String key = key(workflow, fieldId);
        AttemptTracker tracker = trackers.get(key);
        if (tracker == null) {
            throw new IllegalStateException("No liveness attempt begun for " + key);
        }
        int attempt = tracker.current();
        boolean retryAllowed = tracker.record(result);
        results.put(key, result);
        if (result.finalState() == SessionState.PASSED) {
            passedAtMs.put(key, clockMs.getAsLong());
        } else {
            passedAtMs.remove(key);
        }
        return LivenessResultSummary.of(result, attempt, tracker.limit(), retryAllowed, diagnosticsEnabled);
    }

    /**
     * The retry limit is a control against repeated attacks, so once a field's attempts are used up no new
     * session may start (LIV-011) until {@link #reset}.
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the max-retries failure to show instead of a new session, or null while attempts are left
     */
    public synchronized LivenessResultSummary limitReached(String workflow, String fieldId) {
        AttemptTracker tracker = trackers.get(key(workflow, fieldId));
        if (tracker == null || tracker.current() <= tracker.limit()) {
            return null;
        }
        LivenessResult maxRetries = LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.MAX_RETRIES_EXCEEDED).build();
        return LivenessResultSummary.of(maxRetries, tracker.limit(), tracker.limit(), false, false);
    }

    /**
     * Uses the field's pass for one SBI capture, so a face capture can never run on a missing, failed, stale or
     * already used liveness check (stopgap until the capture binder, B-07).
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @param maxAgeMs how old the pass may be
     * @return true if the field's last check passed within {@code maxAgeMs} and the pass was not used yet
     */
    public synchronized boolean consumePass(String workflow, String fieldId, long maxAgeMs) {
        String key = key(workflow, fieldId);
        Long passedAt = passedAtMs.remove(key);
        boolean fresh = passedAt != null && clockMs.getAsLong() - passedAt <= maxAgeMs;
        if (fresh) {
            // The check that allowed this capture is the one the packet's face must carry, whatever runs later
            capturedResults.put(key, results.get(key));
            capturedPolicies.put(key, policies.get(key));
        }
        return fresh;
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the result whose pass let the field's last face capture through, or null when none did
     */
    public synchronized LivenessResult capturedResult(String workflow, String fieldId) {
        return capturedResults.get(key(workflow, fieldId));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the policy of {@link #capturedResult}, or null
     */
    public synchronized LivenessPolicy capturedPolicy(String workflow, String fieldId) {
        return capturedPolicies.get(key(workflow, fieldId));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the last full result for the field, or null
     */
    public synchronized LivenessResult lastResult(String workflow, String fieldId) {
        return results.get(key(workflow, fieldId));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the policy the field last began under, or null
     */
    public synchronized LivenessPolicy lastPolicy(String workflow, String fieldId) {
        return policies.get(key(workflow, fieldId));
    }

    /**
     * Forgets the field's attempts and result, for example for a new registration.
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     */
    public synchronized void reset(String workflow, String fieldId) {
        String key = key(workflow, fieldId);
        trackers.remove(key);
        results.remove(key);
        policies.remove(key);
        capturedResults.remove(key);
        capturedPolicies.remove(key);
        passedAtMs.remove(key);
    }

    private static String key(String workflow, String fieldId) {
        return workflow + "/" + fieldId;
    }
}
