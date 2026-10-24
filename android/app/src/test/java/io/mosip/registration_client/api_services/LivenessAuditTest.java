package io.mosip.registration_client.api_services;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.ChallengeProgress;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** B-12: the API.md §8 liveness audit events, as Android AuditEvent REG-EVT-115 to 125 (TC-AND-18). */
public class LivenessAuditTest {

    private static final String WORKFLOW = "RESIDENT_REGISTRATION";

    private final AuditManagerService audit = mock(AuditManagerService.class);
    private final LivenessAudit liveness = new LivenessAudit(audit);
    private final LivenessPolicy policy = policy();

    @Test
    public void tcAnd18_sessionStart_recordsModeSourceAndOverrides() {
        liveness.sessionStarted(WORKFLOW, policy, false, true);

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_SESSION_STARTED), anyString(), anyString(),
                eq(WORKFLOW), eq("HYBRID"), eq("camerax:front"), eq("L0-camera"), eq("false"), eq("true"));
    }

    @Test
    public void tcAnd18_passivePass_recordsThePassiveAndTheOverallPass() {
        LivenessResult result = LivenessResult.builder(SessionState.PASSED, Decision.LIVE).passiveScore(0.93)
                .frames(12, 20.0).durationMs(1500L).build();

        liveness.finished(WORKFLOW, result, policy, summary(result, 1, true));

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_PASSIVE_PASSED), anyString(), anyString(),
                eq(WORKFLOW), eq("0.93"), eq("12"));
        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_PASSED), anyString(), anyString(),
                eq(WORKFLOW), eq("0.93"), eq(""), eq("1500"), eq("none"));
    }

    @Test
    public void tcAnd18_activeFailure_recordsTheTriggerEachChallengeAndTheFailure() {
        LivenessResult result = LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE).passiveScore(0.42)
                .activeUsed(true).errorCode(LivenessErrorCode.CHALLENGE_FAILED)
                .challenges(Arrays.asList(
                        new ChallengeOutcome(ChallengeType.SMILE, ChallengeProgress.PASSED, 900L),
                        new ChallengeOutcome(ChallengeType.TURN_LEFT, ChallengeProgress.FAILED, 2000L),
                        new ChallengeOutcome(ChallengeType.BLINK, ChallengeProgress.TIMED_OUT, 5000L)))
                .build();

        liveness.finished(WORKFLOW, result, policy, summary(result, 1, true));

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_ACTIVE_TRIGGERED), anyString(), anyString(),
                eq(WORKFLOW), eq("0.42"), eq("0.80"));
        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_CHALLENGE_PASSED), anyString(), anyString(),
                eq(WORKFLOW), eq("SMILE"), eq("900"));
        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_CHALLENGE_FAILED), anyString(), anyString(),
                eq(WORKFLOW), eq("TURN_LEFT"), eq("LIV-009"));
        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_CHALLENGE_TIMEOUT), anyString(), anyString(),
                eq(WORKFLOW), eq("BLINK"));
        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_FAILED), anyString(), anyString(),
                eq(WORKFLOW), eq("LIV-009"), eq("1"), eq("3"));
    }

    @Test
    public void tcAnd18_attack_recordsThePadSuspicion() {
        LivenessResult result = LivenessResult.builder(SessionState.FAILED, Decision.ATTACK_SUSPECTED)
                .passiveScore(0.12).errorCode(LivenessErrorCode.PAD_FAILED).build();

        liveness.finished(WORKFLOW, result, policy, summary(result, 1, true));

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_PAD_SUSPECTED), anyString(), anyString(),
                eq(WORKFLOW), eq("LIV-008"), eq("0.12"));
    }

    @Test
    public void tcAnd18_lastAllowedFailure_recordsMaxRetries() {
        LivenessResult result = LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_TIMEOUT).build();

        liveness.finished(WORKFLOW, result, policy, summary(result, 3, false));

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_MAX_RETRIES), anyString(), anyString(),
                eq(WORKFLOW), eq("BLOCK"));
    }

    @Test
    public void tcAnd18_engineError_recordsTheCode() {
        LivenessResult result = LivenessResult.builder(SessionState.ERROR, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.ENGINE_ERROR).build();

        liveness.finished(WORKFLOW, result, policy, summary(result, 1, true));

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_ENGINE_ERROR), anyString(), anyString(),
                eq("LIV-013"));
    }

    @Test
    public void tcAnd18_cancel_recordsNothing() {
        LivenessResult result = LivenessResult.builder(SessionState.CANCELLED, Decision.INCONCLUSIVE).build();

        liveness.finished(WORKFLOW, result, policy, summary(result, 1, true));

        verifyNoInteractions(audit);
    }

    private static LivenessResultSummary summary(LivenessResult result, int attempt, boolean retryAllowed) {
        return LivenessResultSummary.of(result, attempt, 3, retryAllowed, false);
    }

    private static LivenessPolicy policy() {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigLoader.PREFIX + "enabled", "true");
        return LivenessConfigLoader.load(config, Workflow.RESIDENT_REGISTRATION);
    }
}
