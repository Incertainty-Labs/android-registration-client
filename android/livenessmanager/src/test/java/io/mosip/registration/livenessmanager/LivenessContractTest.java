package io.mosip.registration.livenessmanager;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.ChallengeProgress;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LivenessContractTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void tcAnd_requestCode_isSix() {
        assertEquals(6, LivenessContract.REQUEST_CODE);
    }

    @Test
    public void tcAnd_intent_opensLivenessActivity() {
        Intent intent = LivenessContract.createIntent(context, request());

        assertEquals(LivenessActivity.class.getName(), intent.getComponent().getClassName());
    }

    @Test
    public void tcAnd_request_survivesTheIntent() {
        LivenessContract.Request parsed = LivenessContract.parseRequest(
                LivenessContract.createIntent(context, request()));

        assertEquals("RESIDENT_REGISTRATION", parsed.workflow());
        assertEquals("individualBiometrics", parsed.fieldId());
        assertEquals(2, parsed.attempt());
        assertEquals(config(), parsed.config());
    }

    @Test
    public void tcAnd_request_usesTheApiExtraNames() {
        Intent intent = LivenessContract.createIntent(context, request());

        assertEquals("RESIDENT_REGISTRATION", intent.getStringExtra("liveness.workflow"));
        assertEquals("individualBiometrics", intent.getStringExtra("liveness.fieldId"));
        assertEquals(2, intent.getIntExtra("liveness.attempt", 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void tcAnd_requestWithoutWorkflow_isRejected() {
        Intent intent = LivenessContract.createIntent(context, request());
        intent.removeExtra("liveness.workflow");

        LivenessContract.parseRequest(intent);
    }

    @Test
    public void tcAnd_result_survivesTheIntent() {
        LivenessResultSummary sent = LivenessResultSummary.of(failedChallenge(), 1, 3, true, false);

        LivenessResultSummary got = LivenessContract.parseResult(LivenessContract.resultIntent(sent));

        assertEquals("INCONCLUSIVE", got.decision());
        assertEquals("FAILED", got.finalState());
        assertEquals("LIV-009", got.errorCode());
        assertEquals("liveness.fail.try_again", got.messageKey());
        assertNull(got.passiveScore());
        assertEquals(Arrays.asList("SMILE:PASSED", "TURN_LEFT:FAILED"), got.challenges());
        assertEquals(1, got.attempt());
        assertEquals(3, got.maxAttempts());
        assertTrue(got.retryAllowed());
    }

    @Test
    public void tcAnd_resultWithDiagnostics_carriesTheScore() {
        LivenessResultSummary sent = LivenessResultSummary.of(failedChallenge(), 1, 3, true, true);

        LivenessResultSummary got = LivenessContract.parseResult(LivenessContract.resultIntent(sent));

        assertEquals(0.42, got.passiveScore(), 1e-9);
    }

    @Test
    public void tcAnd_resultJson_holdsOnlyTheUiFields() throws Exception {
        LivenessResultSummary sent = LivenessResultSummary.of(failedChallenge(), 1, 3, true, false);

        JSONObject json = new JSONObject(LivenessContract.resultIntent(sent).getStringExtra("liveness.result"));

        Set<String> names = new HashSet<>();
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            names.add(it.next());
        }
        assertEquals(new HashSet<>(Arrays.asList("decision", "finalState", "errorCode", "messageKey",
                "challenges", "attempt", "maxAttempts", "retryAllowed")), names);
        assertFalse(json.toString().contains("digest"));
    }

    @Test
    public void tcAnd_noResultExtra_parsesAsNull() {
        assertNull(LivenessContract.parseResult(null));
        assertNull(LivenessContract.parseResult(new Intent()));
    }

    private static LivenessContract.Request request() {
        return new LivenessContract.Request("RESIDENT_REGISTRATION", "individualBiometrics", 2, config());
    }

    private static Map<String, String> config() {
        Map<String, String> config = new HashMap<>();
        config.put("mosip.registration.face.liveness.enabled", "true");
        config.put("mosip.registration.face.liveness.max.retries", "3");
        return config;
    }

    private static LivenessResult failedChallenge() {
        return LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_FAILED)
                .passiveScore(0.42)
                .evidenceDigest("ab12")
                .diagnostics(Collections.singletonMap("pad.model", "0.31"))
                .challenges(Arrays.asList(
                        new ChallengeOutcome(ChallengeType.SMILE, ChallengeProgress.PASSED, 900L),
                        new ChallengeOutcome(ChallengeType.TURN_LEFT, ChallengeProgress.FAILED, 2000L)))
                .build();
    }
}
