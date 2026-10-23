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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AttemptTrackerTest {

    @Test
    public void tcAnd_limit_isTheSmallerOfPolicyAndClientLimits() {
        assertEquals(2, new AttemptTracker(policy(3), 2).limit());
        assertEquals(3, new AttemptTracker(policy(3), 5).limit());
    }

    @Test
    public void tcAnd_noClientLimit_usesThePolicyLimit() {
        assertEquals(3, new AttemptTracker(policy(3), 0).limit());
    }

    @Test
    public void tcAnd_firstAttempt_isOne() {
        assertEquals(1, new AttemptTracker(policy(3), 0).current());
    }

    @Test
    public void tcAnd_pass_doesNotUseAnAttempt() {
        AttemptTracker tracker = new AttemptTracker(policy(3), 0);

        assertTrue(tracker.record(result(SessionState.PASSED, null)));
        assertEquals(1, tracker.current());
    }

    @Test
    public void tcAnd_failedChallenge_usesAnAttempt() {
        AttemptTracker tracker = new AttemptTracker(policy(3), 0);

        assertTrue(tracker.record(failed(LivenessErrorCode.CHALLENGE_FAILED, ChallengeProgress.FAILED)));
        assertEquals(2, tracker.current());
    }

    @Test
    public void tcAnd_lastAllowedFailure_allowsNoRetry() {
        AttemptTracker tracker = new AttemptTracker(policy(2), 0);

        assertTrue(tracker.record(failed(LivenessErrorCode.CHALLENGE_TIMEOUT, ChallengeProgress.TIMED_OUT)));
        assertFalse(tracker.record(failed(LivenessErrorCode.PAD_FAILED, null)));
    }

    @Test
    public void tcAnd_deviceErrorAndCancel_doNotUseAnAttempt() {
        AttemptTracker tracker = new AttemptTracker(policy(3), 0);

        tracker.record(result(SessionState.ERROR, LivenessErrorCode.CAMERA_UNAVAILABLE));
        tracker.record(result(SessionState.CANCELLED, null));

        assertEquals(1, tracker.current());
    }

    @Test
    public void tcAnd_multipleFaces_usesAnAttemptOnlyDuringAChallenge() {
        AttemptTracker tracker = new AttemptTracker(policy(3), 0);

        tracker.record(failed(LivenessErrorCode.MULTIPLE_FACES, null));
        assertEquals(1, tracker.current());

        tracker.record(failed(LivenessErrorCode.MULTIPLE_FACES, ChallengeProgress.FAILED));
        assertEquals(2, tracker.current());
    }

    @Test
    public void tcAnd_atTheLimit_evenANonCountingResultAllowsNoRetry() {
        AttemptTracker tracker = new AttemptTracker(policy(1), 0);
        tracker.record(failed(LivenessErrorCode.CHALLENGE_FAILED, ChallengeProgress.FAILED));

        assertFalse(tracker.record(result(SessionState.CANCELLED, null)));
    }

    @Test
    public void tcAnd_reset_startsAgainAtAttemptOne() {
        AttemptTracker tracker = new AttemptTracker(policy(1), 0);
        tracker.record(failed(LivenessErrorCode.CHALLENGE_FAILED, ChallengeProgress.FAILED));

        tracker.reset();

        assertEquals(1, tracker.current());
        assertTrue(tracker.record(result(SessionState.PASSED, null)));
    }

    private static LivenessPolicy policy(int maxRetries) {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigLoader.PREFIX + "enabled", "true");
        config.put(LivenessConfigLoader.PREFIX + "max.retries", String.valueOf(maxRetries));
        return LivenessConfigLoader.load(config, Workflow.RESIDENT_REGISTRATION);
    }

    /** A FAILED result; {@code lastChallenge} null means no challenge ran. */
    private static LivenessResult failed(LivenessErrorCode error, ChallengeProgress lastChallenge) {
        LivenessResult.Builder b = LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE).errorCode(error);
        if (lastChallenge != null) {
            b.challenges(Collections.singletonList(new ChallengeOutcome(ChallengeType.SMILE, lastChallenge, 1000L)));
        }
        return b.build();
    }

    private static LivenessResult result(SessionState state, LivenessErrorCode error) {
        return LivenessResult.builder(state, state == SessionState.PASSED ? Decision.LIVE : Decision.INCONCLUSIVE)
                .errorCode(error).build();
    }
}
