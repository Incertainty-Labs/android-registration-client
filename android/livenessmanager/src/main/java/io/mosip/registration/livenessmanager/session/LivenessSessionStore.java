package io.mosip.registration.livenessmanager.session;

import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
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
        return LivenessResultSummary.of(result, attempt, tracker.limit(), retryAllowed, diagnosticsEnabled);
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
     * Forgets the field's attempts and result, for example for a new registration.
     *
     * @param workflow the {@code Workflow} name
     * @param fieldId  the field
     */
    public synchronized void reset(String workflow, String fieldId) {
        String key = key(workflow, fieldId);
        trackers.remove(key);
        results.remove(key);
    }

    private static String key(String workflow, String fieldId) {
        return workflow + "/" + fieldId;
    }
}
