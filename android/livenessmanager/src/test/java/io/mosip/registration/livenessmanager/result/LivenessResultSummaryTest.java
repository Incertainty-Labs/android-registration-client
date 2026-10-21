package io.mosip.registration.livenessmanager.result;

import org.junit.Test;

import java.util.Arrays;

import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.ChallengeProgress;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LivenessResultSummaryTest {

    @Test
    public void tcAnd02_withPassedResult_mapsLiveWithSuccessMessageAndChallenges() {
        LivenessResult result = LivenessResult.builder(SessionState.PASSED, Decision.LIVE)
                .activeUsed(true)
                .challenges(Arrays.asList(
                        new ChallengeOutcome(ChallengeType.TURN_LEFT, ChallengeProgress.PASSED, 1200L),
                        new ChallengeOutcome(ChallengeType.NEUTRAL_HOLD, ChallengeProgress.PASSED, 700L)))
                .build();

        LivenessResultSummary summary = LivenessResultSummary.of(result, 1, 3, true, false);

        assertEquals("LIVE", summary.decision());
        assertEquals("PASSED", summary.finalState());
        assertNull(summary.errorCode());
        assertEquals("liveness.feedback.success", summary.messageKey());
        assertEquals(Arrays.asList("TURN_LEFT:PASSED", "NEUTRAL_HOLD:PASSED"), summary.challenges());
        assertEquals(1, summary.attempt());
        assertEquals(3, summary.maxAttempts());
        assertTrue(summary.retryAllowed());
    }

    @Test
    public void tcAnd02_withPadFailure_showsOnlyTheGenericMessage() {
        LivenessResult result = LivenessResult.builder(SessionState.FAILED, Decision.ATTACK_SUSPECTED)
                .errorCode(LivenessErrorCode.PAD_FAILED)
                .build();

        LivenessResultSummary summary = LivenessResultSummary.of(result, 1, 3, true, false);

        assertEquals("ATTACK_SUSPECTED", summary.decision());
        assertEquals("LIV-008", summary.errorCode());
        assertEquals("liveness.fail.generic", summary.messageKey());
    }

    @Test
    public void tcAnd02_passiveScore_isHiddenUnlessDiagnosticsAreOn() {
        LivenessResult result = LivenessResult.builder(SessionState.PASSED, Decision.LIVE)
                .passiveScore(0.91)
                .build();

        assertNull(LivenessResultSummary.of(result, 1, 3, true, false).passiveScore());
        assertEquals(Double.valueOf(0.91), LivenessResultSummary.of(result, 1, 3, true, true).passiveScore());
    }

    @Test
    public void tcAnd02_withFailureOnLastAttempt_showsMaxRetriesMessage() {
        LivenessResult result = LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_TIMEOUT)
                .build();

        LivenessResultSummary summary = LivenessResultSummary.of(result, 3, 3, false, false);

        assertEquals("LIV-010", summary.errorCode());
        assertEquals("liveness.fail.max_retries", summary.messageKey());
    }

    @Test
    public void tcAnd02_withCancelOrDeviceError_showsNoMessageOrTheDeviceMessage() {
        LivenessResult cancelled = LivenessResult.builder(SessionState.CANCELLED, Decision.INCONCLUSIVE).build();
        LivenessResult cameraError = LivenessResult.builder(SessionState.ERROR, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CAMERA_UNAVAILABLE)
                .build();

        assertNull(LivenessResultSummary.of(cancelled, 1, 3, true, false).messageKey());
        assertEquals("liveness.error.camera_unavailable",
                LivenessResultSummary.of(cameraError, 1, 3, true, false).messageKey());
    }
}
