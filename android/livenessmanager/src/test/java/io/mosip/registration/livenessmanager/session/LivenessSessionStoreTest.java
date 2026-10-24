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

    @Test(expected = IllegalStateException.class)
    public void tcAnd_completeWithoutBegin_isRejected() {
        store.complete(WORKFLOW, FACE, passed(), false);
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
