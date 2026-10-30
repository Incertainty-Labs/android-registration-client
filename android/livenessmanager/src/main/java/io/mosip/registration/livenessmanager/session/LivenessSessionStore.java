package io.mosip.registration.livenessmanager.session;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import androidx.annotation.VisibleForTesting;

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
    /**
     * The result and policy whose pass let the field's last accepted face capture through, for the packet evidence.
     * A pass that let a capture start is pending until the device's answer is accepted; a capture that fails never
     * replaces the evidence of the face already captured.
     */
    private final Map<String, LivenessResult> capturedResults = new HashMap<>();
    private final Map<String, LivenessPolicy> capturedPolicies = new HashMap<>();
    private final Map<String, LivenessResult> pendingResults = new HashMap<>();
    private final Map<String, LivenessPolicy> pendingPolicies = new HashMap<>();
    /** When each field's last check passed, until a capture uses the pass. */
    private final Map<String, Long> passedAtMs = new HashMap<>();
    /**
     * The same moment on the wall clock, which the device's signed capture timestamp is compared with (capture
     * freshness, B-07): until a capture uses the pass, then for that capture.
     */
    private final Map<String, Long> passedAtEpochMs = new HashMap<>();
    private final Map<String, Long> pendingPassedAtEpochMs = new HashMap<>();
    private final Map<String, Long> capturedPassedAtEpochMs = new HashMap<>();
    private final LongSupplier clockMs;
    private final LongSupplier wallClockMs;

    /**
     * A store on a monotonic clock, so changing the device's date and time can neither keep a pass fresh nor expire
     * it early; the app uses {@link #shared()}.
     */
    public LivenessSessionStore() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    LivenessSessionStore(LongSupplier clockMs) {
        this(clockMs, System::currentTimeMillis);
    }

    /**
     * @param clockMs     the monotonic clock passes age on, in ms
     * @param wallClockMs the wall clock signed capture times are compared with, in epoch ms
     */
    @VisibleForTesting
    public LivenessSessionStore(LongSupplier clockMs, LongSupplier wallClockMs) {
        this.clockMs = clockMs;
        this.wallClockMs = wallClockMs;
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
            passedAtEpochMs.put(key, wallClockMs.getAsLong());
        } else {
            passedAtMs.remove(key);
            passedAtEpochMs.remove(key);
        }
        return LivenessResultSummary.of(result, attempt, tracker.limit(), retryAllowed, diagnosticsEnabled);
    }

    /**
     * Fails the field's attempt because the signed capture that followed its pass was not fresh (LIV-014, B-07): the
     * device did not echo the session nonce, or signed the capture too far from when the check passed. It counts as
     * a failed attempt (TECHNICAL_DESIGN §9.3).
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return what the UI may show, or null if no attempt was begun for the field
     */
    public synchronized LivenessResultSummary rejectCapture(String workflow, String fieldId) {
        String key = key(workflow, fieldId);
        AttemptTracker tracker = trackers.get(key);
        if (tracker == null) {
            return null;
        }
        int attempt = tracker.current();
        LivenessResult stale = LivenessResult.builder(SessionState.FAILED, Decision.ATTACK_SUSPECTED)
                .errorCode(LivenessErrorCode.CAPTURE_BINDING_FAILED).build();
        boolean retryAllowed = tracker.record(stale);
        results.put(key, stale);
        passedAtMs.remove(key);
        passedAtEpochMs.remove(key);
        dropPending(key);
        return LivenessResultSummary.of(stale, attempt, tracker.limit(), retryAllowed, false);
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
     * already used liveness check (stopgap until the capture binder, B-07). The pass is pending until
     * {@link #acceptCapture} or {@link #dropCapture}.
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
        Long passedAtEpoch = passedAtEpochMs.remove(key);
        dropPending(key);
        if (fresh) {
            pendingResults.put(key, results.get(key));
            pendingPolicies.put(key, policies.get(key));
            pendingPassedAtEpochMs.put(key, passedAtEpoch);
        }
        return fresh;
    }

    /**
     * The device's capture was accepted: the pass that let it through is the one the packet's face must carry,
     * whatever runs later.
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return true if a pending pass became the captured one; false when none was pending
     */
    public synchronized boolean acceptCapture(String workflow, String fieldId) {
        String key = key(workflow, fieldId);
        if (!pendingResults.containsKey(key)) {
            return false;
        }
        capturedResults.put(key, pendingResults.remove(key));
        capturedPolicies.put(key, pendingPolicies.remove(key));
        capturedPassedAtEpochMs.put(key, pendingPassedAtEpochMs.remove(key));
        return true;
    }

    /**
     * The capture failed or was cancelled: its pass is dropped, and the face captured before keeps its evidence.
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     */
    public synchronized void dropCapture(String workflow, String fieldId) {
        dropPending(key(workflow, fieldId));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the result whose pass let the capture in progress start, or null when none is pending
     */
    public synchronized LivenessResult pendingResult(String workflow, String fieldId) {
        return pendingResults.get(key(workflow, fieldId));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return when that pass passed, epoch milliseconds, or null when none is pending
     */
    public synchronized Long pendingPassedAtEpochMs(String workflow, String fieldId) {
        return pendingPassedAtEpochMs.get(key(workflow, fieldId));
    }

    private void dropPending(String key) {
        pendingResults.remove(key);
        pendingPolicies.remove(key);
        pendingPassedAtEpochMs.remove(key);
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return when the field's unused pass passed, epoch milliseconds, or null when it has none
     */
    public synchronized Long passedAtEpochMs(String workflow, String fieldId) {
        return passedAtEpochMs.get(key(workflow, fieldId));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return when the pass that let the field's last capture through passed, epoch milliseconds, or null
     */
    public synchronized Long capturedPassedAtEpochMs(String workflow, String fieldId) {
        return capturedPassedAtEpochMs.get(key(workflow, fieldId));
    }

    /**
     * For a field whose workflow is known only after the capture ({@code operatorAuth}, B-09).
     *
     * @param fieldId the field
     * @return the workflow of the field's latest unused pass, or null when it has none
     */
    public synchronized String unusedPassWorkflow(String fieldId) {
        String latest = null;
        long latestAt = Long.MIN_VALUE;
        String suffix = "/" + fieldId;
        for (Map.Entry<String, Long> pass : passedAtMs.entrySet()) {
            if (pass.getKey().endsWith(suffix) && (latest == null || pass.getValue() > latestAt)) {
                latest = pass.getKey().substring(0, pass.getKey().length() - suffix.length());
                latestAt = pass.getValue();
            }
        }
        return latest;
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     * @return the result whose pass let the field's last accepted face capture through, or null when none did
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
        passedAtEpochMs.remove(key);
        capturedPassedAtEpochMs.remove(key);
        dropPending(key);
    }

    private static String key(String workflow, String fieldId) {
        return workflow + "/" + fieldId;
    }
}
