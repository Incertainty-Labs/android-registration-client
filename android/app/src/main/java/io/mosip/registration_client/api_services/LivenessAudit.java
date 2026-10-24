package io.mosip.registration_client.api_services;

import java.util.Locale;

import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;

/**
 * Records the API.md §8 face liveness audit events (Android AuditEvent REG-EVT-115 to 125) with
 * {@code auditWithArguments}: one at the start of a check, and those a finished check's full result shows. Never
 * records a face, only codes, scores and counts. LIVENESS_MAX_RETRIES is recorded once, when the last allowed attempt
 * fails; later checks refused at the limit (LIV-011, before any session) record nothing more.
 */
final class LivenessAudit {

    /** The Android liveness frame source and its certification level (CameraXFrameSource). */
    private static final String SOURCE = "camerax:front";
    private static final String LEVEL = "L0-camera";

    private final AuditManagerService audit;

    LivenessAudit(AuditManagerService audit) {
        this.audit = audit;
    }

    void sessionStarted(String workflow, LivenessPolicy policy, boolean diagnostics, boolean overrideApplied) {
        record(AuditEvent.LIVENESS_SESSION_STARTED, workflow, policy.mode().name(), SOURCE, LEVEL,
                String.valueOf(diagnostics), String.valueOf(overrideApplied));
    }

    /**
     * @param workflow the {@code Workflow} name
     * @param result   the check's full result
     * @param policy   the policy it ran under
     * @param summary  its summary, for the attempt and the limit
     */
    void finished(String workflow, LivenessResult result, LivenessPolicy policy, LivenessResultSummary summary) {
        SessionState state = result.finalState();
        if (state == SessionState.CANCELLED) {
            return;
        }
        LivenessErrorCode error = result.errorCode();
        if (state == SessionState.ERROR) {
            if (error == LivenessErrorCode.ENGINE_ERROR) {
                record(AuditEvent.LIVENESS_ENGINE_ERROR, error.code());
            }
            return;
        }
        String score = twoPlaces(result.passiveScore());
        if (result.activeUsed()) {
            record(AuditEvent.LIVENESS_ACTIVE_TRIGGERED, workflow, score, twoPlaces(policy.passiveThreshold()));
        } else if (state == SessionState.PASSED) {
            record(AuditEvent.LIVENESS_PASSIVE_PASSED, workflow, score, String.valueOf(result.framesAnalysed()));
        }
        for (ChallengeOutcome challenge : result.challenges()) {
            String type = challenge.type().name();
            switch (challenge.outcome()) {
                case PASSED:
                    record(AuditEvent.LIVENESS_CHALLENGE_PASSED, workflow, type, String.valueOf(challenge.durationMs()));
                    break;
                case FAILED:
                    record(AuditEvent.LIVENESS_CHALLENGE_FAILED, workflow, type,
                            error == null ? "failed" : error.code());
                    break;
                case TIMED_OUT:
                    record(AuditEvent.LIVENESS_CHALLENGE_TIMEOUT, workflow, type);
                    break;
                default:
                    break;
            }
        }
        if (result.decision() == Decision.ATTACK_SUSPECTED) {
            record(AuditEvent.LIVENESS_PAD_SUSPECTED, workflow, error == null ? "unknown" : error.code(), score);
        }
        if (state == SessionState.PASSED) {
            record(AuditEvent.LIVENESS_PASSED, workflow, score, result.challengeSummary(),
                    String.valueOf(result.durationMs()),
                    result.evidenceDigest() == null ? "none" : result.evidenceDigest());
        } else if (state == SessionState.FAILED) {
            record(AuditEvent.LIVENESS_FAILED, workflow, error == null ? "none" : error.code(),
                    String.valueOf(summary.attempt()), String.valueOf(summary.maxAttempts()));
            if (!summary.retryAllowed()) {
                record(AuditEvent.LIVENESS_MAX_RETRIES, workflow, policy.onMaxRetries().name());
            }
        }
    }

    private void record(AuditEvent event, String... arguments) {
        audit.auditWithArguments(event, Components.REGISTRATION.getId(), Components.REGISTRATION.getName(),
                arguments);
    }

    private static String twoPlaces(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
