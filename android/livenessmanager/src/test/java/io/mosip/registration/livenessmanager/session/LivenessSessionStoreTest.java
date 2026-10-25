package io.mosip.registration.livenessmanager.session;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LivenessSessionStoreTest {

    private static final String WORKFLOW = "RESIDENT_REGISTRATION";
    private static final String FACE = "individualBiometrics";

    private final LivenessSessionStore store = new LivenessSessionStore();

    @Test
    public void tcAnd_firstBegin_isAttemptOne() {
        assertEquals(1, store.begin(WORKFLOW, FACE, policy(3), 0));
    }

    @Test
    public void tcAnd_complete_returnsTheSummaryOfTheAttemptJustRun() {
        store.begin(WORKFLOW, FACE, policy(3), 0);

        LivenessResultSummary summary = store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertEquals(1, summary.attempt());
        assertEquals(3, summary.maxAttempts());
        assertTrue(summary.retryAllowed());
        assertEquals("FAILED", summary.finalState());
        assertEquals(2, store.begin(WORKFLOW, FACE, policy(3), 0));
    }

    @Test
    public void tcAnd_complete_keepsTheFullResultForPacketEvidence() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        LivenessResult result = passed();

        store.complete(WORKFLOW, FACE, result, false);

        assertSame(result, store.lastResult(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd_lastAttemptFailed_showsMaxRetriesAndNoRetry() {
        store.begin(WORKFLOW, FACE, policy(1), 0);

        LivenessResultSummary summary = store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertFalse(summary.retryAllowed());
        assertEquals("liveness.fail.max_retries", summary.messageKey());
    }

    @Test
    public void tcAnd_fields_countTheirAttemptsSeparately() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertEquals(1, store.begin("OPERATOR_AUTH", "operatorAuth", policy(3), 0));
        assertNull(store.lastResult("OPERATOR_AUTH", "operatorAuth"));
    }

    @Test
    public void tcAnd_reset_clearsTheAttemptsAndTheResult() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        store.reset(WORKFLOW, FACE);

        assertNull(store.lastResult(WORKFLOW, FACE));
        assertEquals(1, store.begin(WORKFLOW, FACE, policy(3), 0));
    }

    @Test
    public void tcAnd_attemptsLeft_noLimitReached() {
        store.begin(WORKFLOW, FACE, policy(2), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertNull(store.limitReached(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd_attemptsUsedUp_limitReachedIsTheMaxRetriesFailure() {
        store.begin(WORKFLOW, FACE, policy(1), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        LivenessResultSummary summary = store.limitReached(WORKFLOW, FACE);

        assertEquals("FAILED", summary.finalState());
        assertEquals("LIV-011", summary.errorCode());
        assertEquals("liveness.fail.max_retries", summary.messageKey());
        assertFalse(summary.retryAllowed());
        assertEquals(1, summary.attempt());
        assertEquals(1, summary.maxAttempts());
    }

    @Test
    public void tcAnd_fieldNeverBegun_noLimitReached() {
        assertNull(store.limitReached(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd05_freshPass_allowsOneCaptureOnly() {
        long[] now = {1_000L};
        LivenessSessionStore timed = new LivenessSessionStore(() -> now[0]);
        timed.begin(WORKFLOW, FACE, policy(3), 0);
        timed.complete(WORKFLOW, FACE, passed(), false);
        now[0] += 5_000L;

        assertTrue(timed.consumePass(WORKFLOW, FACE, 120_000L));
        assertFalse(timed.consumePass(WORKFLOW, FACE, 120_000L));
    }

    @Test
    public void tcAnd05_stalePass_allowsNoCapture() {
        long[] now = {1_000L};
        LivenessSessionStore timed = new LivenessSessionStore(() -> now[0]);
        timed.begin(WORKFLOW, FACE, policy(3), 0);
        timed.complete(WORKFLOW, FACE, passed(), false);
        now[0] += 120_001L;

        assertFalse(timed.consumePass(WORKFLOW, FACE, 120_000L));
    }

    @Test
    public void tcAnd06_failure_allowsNoCapture() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertFalse(store.consumePass(WORKFLOW, FACE, 120_000L));
    }

    @Test
    public void tcAnd06_noCheck_allowsNoCapture() {
        assertFalse(store.consumePass(WORKFLOW, FACE, 120_000L));
    }

    @Test
    public void tcAnd05_aNewPass_allowsTheNextCapture() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, passed(), false);
        store.consumePass(WORKFLOW, FACE, 120_000L);

        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, passed(), false);

        assertTrue(store.consumePass(WORKFLOW, FACE, 120_000L));
    }

    @Test
    public void tcAnd18_store_keepsThePolicyTheFieldRanUnder() {
        LivenessPolicy policy = policy(3);
        store.begin(WORKFLOW, FACE, policy, 0);

        assertSame(policy, store.lastPolicy(WORKFLOW, FACE));
        assertNull(store.lastPolicy("OPERATOR_AUTH", "operatorAuth"));
    }

    @Test
    public void tcAnd18_reset_forgetsThePolicy() {
        store.begin(WORKFLOW, FACE, policy(3), 0);

        store.reset(WORKFLOW, FACE);

        assertNull(store.lastPolicy(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd18_capture_keepsTheResultThatAllowedIt() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        LivenessResult passA = passed();
        store.complete(WORKFLOW, FACE, passA, false);
        store.consumePass(WORKFLOW, FACE, 120_000L);

        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertSame(passA, store.capturedResult(WORKFLOW, FACE));
        assertNotNull(store.capturedPolicy(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd18_anUnusedLaterPass_doesNotReplaceTheCapturedResult() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        LivenessResult passA = passed();
        store.complete(WORKFLOW, FACE, passA, false);
        store.consumePass(WORKFLOW, FACE, 120_000L);

        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, passed(), false);

        assertSame(passA, store.capturedResult(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd18_noCapture_hasNoCapturedResult_andResetClearsIt() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, passed(), false);
        assertNull(store.capturedResult(WORKFLOW, FACE));

        store.consumePass(WORKFLOW, FACE, 120_000L);
        store.reset(WORKFLOW, FACE);

        assertNull(store.capturedResult(WORKFLOW, FACE));
        assertNull(store.capturedPolicy(WORKFLOW, FACE));
    }

    @Test(expected = IllegalStateException.class)
    public void tcAnd_completeWithoutBegin_isRejected() {
        store.complete(WORKFLOW, FACE, passed(), false);
    }

    @Test
    public void tcFresh_aPass_keepsWhenItPassedOnTheWallClockForTheCaptureThatUsesIt() {
        long[] wall = {1_700_000_000_000L};
        LivenessSessionStore timed = new LivenessSessionStore(() -> 5L, () -> wall[0]);
        timed.begin(WORKFLOW, FACE, policy(3), 0);
        timed.complete(WORKFLOW, FACE, passed(), false);
        wall[0] += 9_000L;

        assertEquals(Long.valueOf(1_700_000_000_000L), timed.passedAtEpochMs(WORKFLOW, FACE));
        assertTrue(timed.consumePass(WORKFLOW, FACE, 120_000L));
        assertNull(timed.passedAtEpochMs(WORKFLOW, FACE));
        assertEquals(Long.valueOf(1_700_000_000_000L), timed.capturedPassedAtEpochMs(WORKFLOW, FACE));
    }

    @Test
    public void tcFresh_aFailedCheck_hasNoPassTime() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, challengeFailed(), false);

        assertNull(store.passedAtEpochMs(WORKFLOW, FACE));
        assertNull(store.capturedPassedAtEpochMs(WORKFLOW, FACE));
    }

    @Test
    public void tcFresh_unusedPassWorkflow_isTheFieldsLatestUnusedPass() {
        long[] now = {10L};
        LivenessSessionStore timed = new LivenessSessionStore(() -> now[0], () -> now[0]);
        for (String workflow : new String[] {"OPERATOR_LOGIN", "OPERATOR_PACKET_AUTH"}) {
            timed.begin(workflow, "operatorAuth", policy(3), 0);
            timed.complete(workflow, "operatorAuth", passed(), false);
            now[0] += 10L;
        }

        assertEquals("OPERATOR_PACKET_AUTH", timed.unusedPassWorkflow("operatorAuth"));
        timed.consumePass("OPERATOR_PACKET_AUTH", "operatorAuth", 120_000L);
        assertEquals("OPERATOR_LOGIN", timed.unusedPassWorkflow("operatorAuth"));
        assertNull(timed.unusedPassWorkflow(FACE));
    }

    @Test
    public void tcFresh_aStaleCapture_failsTheAttemptWithLiv014() {
        store.begin(WORKFLOW, FACE, policy(3), 0);
        store.complete(WORKFLOW, FACE, passed(), false);
        store.consumePass(WORKFLOW, FACE, 120_000L);

        LivenessResultSummary summary = store.rejectCapture(WORKFLOW, FACE);

        assertEquals(1, summary.attempt());
        assertEquals("FAILED", summary.finalState());
        assertEquals("LIV-014", summary.errorCode());
        assertTrue(summary.retryAllowed());
        assertEquals(LivenessErrorCode.CAPTURE_BINDING_FAILED, store.lastResult(WORKFLOW, FACE).errorCode());
        assertEquals(2, store.begin(WORKFLOW, FACE, policy(3), 0));
    }

    @Test
    public void tcFresh_aStaleCaptureWithoutACheck_recordsNothing() {
        assertNull(store.rejectCapture(WORKFLOW, FACE));
    }

    private static LivenessPolicy policy(int maxRetries) {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigLoader.PREFIX + "enabled", "true");
        config.put(LivenessConfigLoader.PREFIX + "max.retries", String.valueOf(maxRetries));
        return LivenessConfigLoader.load(config, Workflow.RESIDENT_REGISTRATION);
    }

    private static LivenessResult challengeFailed() {
        return LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_FAILED)
                .challenges(Collections.singletonList(
                        new ChallengeOutcome(ChallengeType.TURN_LEFT, ChallengeProgress.FAILED, 2000L)))
                .build();
    }

    private static LivenessResult passed() {
        return LivenessResult.builder(SessionState.PASSED, Decision.LIVE).build();
    }
}
