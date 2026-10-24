package io.mosip.registration_client.api_services;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;

import javax.inject.Singleton;

import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.service.FaceAuthService;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.spi.LocalConfigService;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.livenessmanager.LivenessContract;
import io.mosip.registration.livenessmanager.LivenessSettings;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;
import io.mosip.registration.livenessmanager.session.AttemptTracker;
import io.mosip.registration.livenessmanager.session.LivenessSessionStore;
import io.mosip.registration_client.model.LivenessPigeon;

/**
 * The Pigeon {@code LivenessApi} (API.md §10): opens {@code LivenessActivity} and replies with its result. Every
 * call is completed exactly once, so the Dart side never waits forever.
 */
@Singleton
public class LivenessApiImpl implements LivenessPigeon.LivenessApi {

    static final String LIVENESS_DISABLED = "LIVENESS_DISABLED";
    static final String LIVENESS_IN_PROGRESS = "LIVENESS_IN_PROGRESS";
    static final String LIVENESS_BAD_REQUEST = "LIVENESS_BAD_REQUEST";
    static final String LIVENESS_START_FAILED = "LIVENESS_START_FAILED";
    static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    static final String TEST_OVERRIDES_NOT_ALLOWED = "TEST_OVERRIDES_NOT_ALLOWED";
    static final String LIVENESS_REQUIRED = "LIVENESS_REQUIRED";

    private static final String TAG = LivenessApiImpl.class.getSimpleName();
    private static final String SYNCED_PATTERN = LivenessConfigLoader.PREFIX + "%";
    private static final String DIAGNOSTICS_KEY = LivenessConfigLoader.PREFIX + "diagnostics.enabled";

    private final GlobalParamRepository globalParamRepository;
    private final LocalConfigService localConfigService;
    private final String overrides;
    private final boolean testScreen;
    /** Overrides set from the B-21 test screen, applied on top of the build's; "" for none. */
    private String testOverrides = "";
    private final LivenessSessionStore store = LivenessSessionStore.shared();
    private Activity activity;

    /** Operator and supervisor face authentication (B-09); null until {@link #setFaceAuth}. */
    private FaceAuthService faceAuthService;
    private OperatorAuthCapture operatorAuthCapture;
    private AuditManagerService auditManagerService;

    /** The Dart call waiting for {@code LivenessActivity}; null when none. */
    private Pending pending;

    /**
     * @param globalParamRepository the synced config
     * @param localConfigService    the operator's local preferences (Global Config Settings), which win over the
     *                              synced config
     * @param overrides             the build's liveness overrides ({@code BuildConfig.LIVENESS_OVERRIDES}), which
     *                              win over both; null for none
     * @param testScreen            whether this build has the B-21 liveness test screen, which may set
     *                              {@link #setTestOverrides} (debug builds, or {@code mosip.liveness.testScreen})
     */
    public LivenessApiImpl(GlobalParamRepository globalParamRepository, LocalConfigService localConfigService,
                           String overrides, boolean testScreen) {
        this.globalParamRepository = globalParamRepository;
        this.localConfigService = localConfigService;
        this.overrides = overrides;
        this.testScreen = testScreen;
        if (overrides != null && !overrides.trim().isEmpty()) {
            Log.w(TAG, "Liveness config overrides are set in this build: " + overrides);
        }
    }

    /**
     * @param faceAuth the 1:1 face matcher
     * @param capture  where the authenticating face is held after its SBI capture
     * @param audit    records each face authentication
     */
    public void setFaceAuth(FaceAuthService faceAuth, OperatorAuthCapture capture, AuditManagerService audit) {
        this.faceAuthService = faceAuth;
        this.operatorAuthCapture = capture;
        this.auditManagerService = audit;
    }

    /** @param activity starts {@code LivenessActivity} and passes its result to {@link #onActivityResult} */
    public void setCallbackActivity(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void startLiveness(@NonNull String workflow, @NonNull String fieldId,
                              @NonNull LivenessPigeon.Result<LivenessPigeon.LivenessResultDto> result) {
        if (pending != null) {
            result.error(error(LIVENESS_IN_PROGRESS, "A liveness check is already open"));
            return;
        }
        Workflow parsed;
        try {
            parsed = Workflow.valueOf(workflow);
        } catch (IllegalArgumentException e) {
            result.error(error(LIVENESS_BAD_REQUEST, "Unknown workflow " + workflow));
            return;
        }
        Map<String, String> config = config();
        LivenessPolicy policy = LivenessConfigLoader.load(config, parsed);
        if (!policy.enabled()) {
            result.error(error(LIVENESS_DISABLED, "Liveness is off for " + workflow));
            return;
        }
        if (activity == null) {
            result.error(error(LIVENESS_START_FAILED, "No activity to open the liveness screen"));
            return;
        }
        int attempt = store.begin(workflow, fieldId, policy, clientRetryLimit(config));
        LivenessResultSummary limit = store.limitReached(workflow, fieldId);
        if (limit != null) {
            // Attempts used up: the limit guards against repeated attacks, so no new session (LIV-011)
            result.success(toDto(limit));
            return;
        }
        pending = new Pending(workflow, fieldId, "true".equalsIgnoreCase(config.get(DIAGNOSTICS_KEY)), result);
        try {
            activity.startActivityForResult(LivenessContract.createIntent(activity,
                    new LivenessContract.Request(workflow, fieldId, attempt, config)), LivenessContract.REQUEST_CODE);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot open the liveness screen", e);
            pending = null;
            result.error(error(LIVENESS_START_FAILED, e.getMessage()));
        }
    }

    /**
     * Replies to the waiting call with the activity's result.
     *
     * @param requestCode the request code
     * @param resultCode  the result code
     * @param data        the data intent, or null
     * @return true if the request code was the liveness one
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != LivenessContract.REQUEST_CODE) {
            return false;
        }
        Pending call = pending;
        pending = null;
        if (call == null) {
            return true;
        }
        LivenessResultSummary summary = LivenessContract.parseResult(data);
        if (summary == null) {
            // The activity returned no result (it died): report a cancel, as LivenessContract says
            summary = store.complete(call.workflow, call.fieldId,
                    LivenessResult.builder(SessionState.CANCELLED, Decision.INCONCLUSIVE).build(), call.diagnostics);
        }
        call.result.success(toDto(summary));
        return true;
    }

    @Override
    public void getLivenessConfig(@NonNull String workflow, @NonNull LivenessPigeon.Result<Map<String, String>> result) {
        try {
            Map<String, String> config = config();
            LivenessPolicy policy = LivenessConfigLoader.load(config, Workflow.valueOf(workflow));
            Map<String, String> hints = new HashMap<>();
            hints.put("enabled", String.valueOf(policy.enabled()));
            hints.put("mode", policy.mode().name());
            hints.put("maxAttempts", String.valueOf(new AttemptTracker(policy, clientRetryLimit(config)).limit()));
            hints.put("onMaxRetries", policy.onMaxRetries().name());
            hints.put("activeEnabled", String.valueOf(policy.activeEnabled()));
            hints.put("testScreen", String.valueOf(testScreen));
            hints.put("faceAuthAvailable", String.valueOf(faceAuthService != null && faceAuthService.isAvailable()));
            result.success(hints);
        } catch (IllegalArgumentException e) {
            result.error(error(LIVENESS_BAD_REQUEST, "Unknown workflow " + workflow));
        }
    }

    @Override
    public void resetAttempts(@NonNull String workflow, @NonNull String fieldId,
                              @NonNull LivenessPigeon.Result<Void> result) {
        if (pending != null) {
            // The open check still needs its attempt to complete
            result.error(error(LIVENESS_IN_PROGRESS, "A liveness check is open"));
            return;
        }
        store.reset(workflow, fieldId);
        result.success(null);
    }

    @Override
    public void setTestOverrides(@NonNull String overrides, @NonNull LivenessPigeon.Result<Void> result) {
        if (!testScreen) {
            result.error(error(TEST_OVERRIDES_NOT_ALLOWED, "This build has no liveness test screen"));
            return;
        }
        testOverrides = overrides;
        result.success(null);
    }

    /** How long a liveness pass covers the face capture that follows it. */
    static final long PASS_MAX_AGE_MS = 120_000L;

    /**
     * Whether a face SBI capture may run for a field now: liveness is off for its workflow, or the field's last
     * check passed within {@link #PASS_MAX_AGE_MS} and that pass is used for this capture. Stopgap until the
     * capture binder (B-07) ties the capture to the live session.
     *
     * @param fieldId {@code operatorBiometrics} for operator onboarding; any other field is a resident's
     * @return true if the capture may run
     */
    public boolean faceCaptureAllowed(String fieldId) {
        if (OperatorAuthCapture.FIELD_ID.equals(fieldId)) {
            // Its workflow is known only at the match, which uses the pass itself (authenticateWithFace)
            return true;
        }
        Workflow workflow = "operatorBiometrics".equals(fieldId)
                ? Workflow.OPERATOR_ONBOARDING : Workflow.RESIDENT_REGISTRATION;
        if (!LivenessConfigLoader.load(config(), workflow).enabled()) {
            return true;
        }
        return store.consumePass(workflow.name(), fieldId, PASS_MAX_AGE_MS);
    }

    /**
     * Matches the face just captured under {@code operatorAuth} against the user's templates (B-09, API.md §15).
     * The liveness check and the SBI capture run first, from Flutter; this needs the workflow's fresh, unused
     * liveness pass for {@code operatorAuth}, so a face is never matched without a live check. Fails closed: liveness
     * off, no pass, no capture, no template or no matcher is never authenticated.
     */
    @Override
    public void authenticateWithFace(@NonNull String userId, @NonNull String workflow,
                                     @NonNull LivenessPigeon.Result<LivenessPigeon.AuthResultDto> result) {
        Workflow parsed;
        try {
            parsed = Workflow.valueOf(workflow);
        } catch (IllegalArgumentException e) {
            result.error(error(LIVENESS_BAD_REQUEST, "Unknown workflow " + workflow));
            return;
        }
        List<BiometricsDto> capture = operatorAuthCapture == null ? Collections.emptyList()
                : operatorAuthCapture.take();
        String errorCode;
        String messageKey;
        if (!LivenessConfigLoader.load(config(), parsed).enabled()) {
            errorCode = LIVENESS_DISABLED;
            messageKey = "liveness.auth.unavailable";
        } else if (!store.consumePass(workflow, OperatorAuthCapture.FIELD_ID, PASS_MAX_AGE_MS)) {
            errorCode = LIVENESS_REQUIRED;
            messageKey = "liveness.fail.try_again";
        } else if (faceAuthService == null) {
            errorCode = "FACE_NO_PROVIDER";
            messageKey = "liveness.auth.unavailable";
        } else {
            FaceAuthService.Outcome outcome = faceAuthService.authenticate(userId, capture);
            errorCode = outcome == FaceAuthService.Outcome.MATCHED ? null : "FACE_" + outcome.name();
            messageKey = authMessageKey(outcome);
        }
        boolean authenticated = errorCode == null;
        if (auditManagerService != null) {
            auditManagerService.audit(authenticated ? AuditEvent.CREATE_PACKET_AUTH : AuditEvent.CREATE_PACKET_AUTH_FAILED,
                    Components.REGISTRATION, "Face authentication " + workflow + ": "
                            + (authenticated ? "matched" : errorCode));
        }
        result.success(new LivenessPigeon.AuthResultDto.Builder()
                .setAuthenticated(authenticated)
                .setErrorCode(errorCode)
                .setMessageKey(messageKey)
                .build());
    }

    @Override
    public void seedFaceTemplate(@NonNull String userId, @NonNull LivenessPigeon.Result<Void> result) {
        if (!testScreen) {
            result.error(error(TEST_OVERRIDES_NOT_ALLOWED, "This build has no liveness test screen"));
            return;
        }
        List<BiometricsDto> capture = operatorAuthCapture == null ? Collections.emptyList()
                : operatorAuthCapture.take();
        if (faceAuthService == null || !faceAuthService.seedTemplate(userId, capture)) {
            result.error(error("FACE_NO_CAPTURE", "No face captured to save"));
            return;
        }
        Log.w(TAG, "Debug face template seeded for " + userId);
        result.success(null);
    }

    private static String authMessageKey(FaceAuthService.Outcome outcome) {
        switch (outcome) {
            case MATCHED:
                return "liveness.badge.verified";
            case NOT_MATCHED:
                return "liveness.auth.no_match";
            case NO_TEMPLATE:
                return "liveness.auth.no_template";
            case NO_CAPTURE:
                return "liveness.auth.no_capture";
            default:
                return "liveness.auth.unavailable";
        }
    }

    /**
     * Synced config, then local preferences, then the build's overrides, then the test screen's; each wins over
     * the one before.
     */
    private Map<String, String> config() {
        Map<String, Object> settings = new HashMap<>(globalParamRepository.getGlobalParamsByPattern(SYNCED_PATTERN));
        String faceRetries = globalParamRepository.getGlobalParamValue(LivenessSettings.FACE_RETRIES_KEY);
        if (faceRetries != null) {
            settings.put(LivenessSettings.FACE_RETRIES_KEY, faceRetries);
        }
        Map<String, String> local = localConfigService.getLocalConfigurations();
        if (local != null) {
            settings.putAll(local);
        }
        return LivenessSettings.merge(settings, (overrides == null ? "" : overrides) + ";" + testOverrides);
    }

    private static int clientRetryLimit(Map<String, String> config) {
        try {
            String value = config.get(LivenessSettings.FACE_RETRIES_KEY);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LivenessPigeon.LivenessResultDto toDto(LivenessResultSummary summary) {
        return new LivenessPigeon.LivenessResultDto.Builder()
                .setDecision(summary.decision())
                .setFinalState(summary.finalState())
                .setErrorCode(summary.errorCode())
                .setMessageKey(summary.messageKey())
                .setPassiveScore(summary.passiveScore())
                .setChallenges(summary.challenges())
                .setAttempt((long) summary.attempt())
                .setMaxAttempts((long) summary.maxAttempts())
                .setRetryAllowed(summary.retryAllowed())
                .build();
    }

    private static LivenessPigeon.FlutterError error(String code, String message) {
        return new LivenessPigeon.FlutterError(code, message, null);
    }

    /** A Dart call waiting for the activity. */
    private static final class Pending {
        private final String workflow;
        private final String fieldId;
        private final boolean diagnostics;
        private final LivenessPigeon.Result<LivenessPigeon.LivenessResultDto> result;

        Pending(String workflow, String fieldId, boolean diagnostics,
                LivenessPigeon.Result<LivenessPigeon.LivenessResultDto> result) {
            this.workflow = workflow;
            this.fieldId = fieldId;
            this.diagnostics = diagnostics;
            this.result = result;
        }
    }
}
