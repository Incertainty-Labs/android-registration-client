package io.mosip.registration_client.api_services;

import android.app.Activity;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.spi.LocalConfigService;
import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.ChallengeProgress;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.livenessmanager.LivenessActivity;
import io.mosip.registration.livenessmanager.LivenessContract;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;
import io.mosip.registration.livenessmanager.session.LivenessSessionStore;
import io.mosip.registration_client.model.LivenessPigeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B-05: the Pigeon LivenessApi always completes, and maps the activity's result to the DTO (TC-AND-02). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LivenessApiImplTest {

    private static final String WORKFLOW = "RESIDENT_REGISTRATION";
    private static final String FACE = "individualBiometrics";
    private static final String PREFIX = "mosip.registration.face.liveness.";

    private final GlobalParamRepository globalParams = mock(GlobalParamRepository.class);
    private final Map<String, Object> synced = new HashMap<>();
    private final LocalConfigService localConfig = mock(LocalConfigService.class);
    private final Map<String, String> localPreferences = new HashMap<>();
    private Activity activity;

    @Before
    public void setUp() {
        when(globalParams.getGlobalParamsByPattern(PREFIX + "%")).thenReturn(synced);
        when(localConfig.getLocalConfigurations()).thenReturn(localPreferences);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        LivenessSessionStore.shared().reset(WORKFLOW, FACE);
    }

    @After
    public void tearDown() {
        LivenessSessionStore.shared().reset(WORKFLOW, FACE);
    }

    @Test
    public void tcAnd02_livenessOff_failsLoudlyAndOpensNothing() {
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();

        api(null).startLiveness(WORKFLOW, FACE, reply);

        assertEquals("LIVENESS_DISABLED", reply.code());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test
    public void tcAnd02_syncedEnabled_opensTheActivityWithRequestCodeSix() {
        synced.put(PREFIX + "enabled", "true");
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();

        api(null).startLiveness(WORKFLOW, FACE, reply);

        ShadowActivity.IntentForResult started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(LivenessContract.REQUEST_CODE, started.requestCode);
        assertEquals(LivenessActivity.class.getName(), started.intent.getComponent().getClassName());
        LivenessContract.Request request = LivenessContract.parseRequest(started.intent);
        assertEquals(WORKFLOW, request.workflow());
        assertEquals(FACE, request.fieldId());
        assertEquals("true", request.config().get(PREFIX + "enabled"));
        assertEquals(0, reply.calls);
    }

    @Test
    public void tcAnd02_buildOverride_turnsLivenessOn() {
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();

        api("enabled=true;stub.force=PASS_ACTIVE").startLiveness(WORKFLOW, FACE, reply);

        LivenessContract.Request request = LivenessContract.parseRequest(
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent);
        assertEquals("PASS_ACTIVE", request.config().get(PREFIX + "stub.force"));
    }

    @Test
    public void tcAnd02_result_isMappedToTheDto() {
        LivenessApiImpl api = api("enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();
        api.startLiveness(WORKFLOW, FACE, reply);
        LivenessResultSummary summary = LivenessResultSummary.of(failedChallenge(), 1, 3, true, false);

        assertTrue(api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK,
                LivenessContract.resultIntent(summary)));

        LivenessPigeon.LivenessResultDto dto = reply.value;
        assertEquals(1, reply.calls);
        assertEquals("INCONCLUSIVE", dto.getDecision());
        assertEquals("FAILED", dto.getFinalState());
        assertEquals("LIV-009", dto.getErrorCode());
        assertEquals("liveness.fail.try_again", dto.getMessageKey());
        assertNull(dto.getPassiveScore());
        assertEquals(Arrays.asList("SMILE:PASSED", "TURN_LEFT:FAILED"), dto.getChallenges());
        assertEquals(Long.valueOf(1), dto.getAttempt());
        assertEquals(Long.valueOf(3), dto.getMaxAttempts());
        assertEquals(Boolean.TRUE, dto.getRetryAllowed());
    }

    @Test
    public void tcAnd02_noResultExtra_completesAsCancelled() {
        LivenessApiImpl api = api("enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();
        api.startLiveness(WORKFLOW, FACE, reply);

        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_CANCELED, null);

        assertEquals("CANCELLED", reply.value.getFinalState());
        assertEquals(Long.valueOf(1), reply.value.getAttempt());
    }

    @Test
    public void tcAnd02_secondCallWhileWaiting_isRejectedAndTheFirstStillCompletes() {
        LivenessApiImpl api = api("enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> first = new Reply<>();
        Reply<LivenessPigeon.LivenessResultDto> second = new Reply<>();
        api.startLiveness(WORKFLOW, FACE, first);

        api.startLiveness(WORKFLOW, FACE, second);
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_CANCELED, null);

        assertEquals("LIVENESS_IN_PROGRESS", second.code());
        assertEquals(1, first.calls);
        assertEquals("CANCELLED", first.value.getFinalState());
    }

    @Test
    public void tcAnd02_otherRequestCodes_areLeftToTheSbiHandler() {
        LivenessApiImpl api = api("enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();
        api.startLiveness(WORKFLOW, FACE, reply);

        assertFalse(api.onActivityResult(3, Activity.RESULT_OK, new Intent()));
        assertEquals(0, reply.calls);
    }

    @Test
    public void tcAnd02_unknownWorkflow_isRejected() {
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();

        api("enabled=true").startLiveness("NOT_A_WORKFLOW", FACE, reply);

        assertEquals("LIVENESS_BAD_REQUEST", reply.code());
    }

    @Test
    public void tcAnd02_noActivity_failsInsteadOfWaitingForever() {
        LivenessApiImpl api = new LivenessApiImpl(globalParams, localConfig, "enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();

        api.startLiveness(WORKFLOW, FACE, reply);

        assertEquals("LIVENESS_START_FAILED", reply.code());
    }

    @Test
    public void tcAnd02_config_givesTheUiItsHints() {
        synced.put(PREFIX + "max.retries", "3");
        when(globalParams.getGlobalParamValue("mosip.registration.num_of_face_retries")).thenReturn("2");
        Reply<Map<String, String>> reply = new Reply<>();

        api("enabled=true").getLivenessConfig(WORKFLOW, reply);

        Map<String, String> config = reply.value;
        assertEquals("true", config.get("enabled"));
        assertEquals("2", config.get("maxAttempts"));
        assertEquals("HYBRID", config.get("mode"));
        assertEquals("BLOCK", config.get("onMaxRetries"));
        assertEquals("true", config.get("activeEnabled"));
    }

    @Test
    public void tcAnd02_resetAttempts_startsTheFieldAgain() {
        LivenessApiImpl api = api("enabled=true");
        api.startLiveness(WORKFLOW, FACE, new Reply<>());
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK, LivenessContract.resultIntent(
                LivenessSessionStore.shared().complete(WORKFLOW, FACE, failedChallenge(), false)));
        Reply<Void> reply = new Reply<>();

        api.resetAttempts(WORKFLOW, FACE, reply);

        assertEquals(1, reply.calls);
        assertNull(LivenessSessionStore.shared().lastResult(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd02_faceAuth_isNotImplementedYet() {
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        api("enabled=true").authenticateWithFace("110011", "OPERATOR_AUTH", reply);

        assertEquals("NOT_IMPLEMENTED", reply.code());
    }

    @Test
    public void tcAnd02_attemptsUsedUp_repliesMaxRetriesWithoutOpeningTheScreen() {
        LivenessApiImpl api = api("enabled=true;max.retries=1");
        api.startLiveness(WORKFLOW, FACE, new Reply<>());
        Shadows.shadowOf(activity).getNextStartedActivityForResult();
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK, LivenessContract.resultIntent(
                LivenessSessionStore.shared().complete(WORKFLOW, FACE, failedChallenge(), false)));
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();

        api.startLiveness(WORKFLOW, FACE, reply);

        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
        assertEquals("FAILED", reply.value.getFinalState());
        assertEquals("LIV-011", reply.value.getErrorCode());
        assertEquals("liveness.fail.max_retries", reply.value.getMessageKey());
        assertEquals(Boolean.FALSE, reply.value.getRetryAllowed());
    }

    @Test
    public void tcAnd17_localPreferences_winOverSyncedValues() {
        synced.put(PREFIX + "enabled", "false");
        localPreferences.put(PREFIX + "enabled", "true");
        localPreferences.put(PREFIX + "mode", "PASSIVE");
        Reply<Map<String, String>> reply = new Reply<>();

        api(null).getLivenessConfig(WORKFLOW, reply);

        assertEquals("true", reply.value.get("enabled"));
        assertEquals("PASSIVE", reply.value.get("mode"));
    }

    @Test
    public void tcAnd02_buildOverrides_winOverLocalPreferences() {
        localPreferences.put(PREFIX + "mode", "PASSIVE");
        Reply<Map<String, String>> reply = new Reply<>();

        api("enabled=true;mode=ACTIVE").getLivenessConfig(WORKFLOW, reply);

        assertEquals("ACTIVE", reply.value.get("mode"));
    }

    @Test
    public void tcAnd02_resetWhileACheckIsOpen_isRefusedAndTheCheckStillCompletes() {
        LivenessApiImpl api = api("enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> check = new Reply<>();
        api.startLiveness(WORKFLOW, FACE, check);
        Reply<Void> reset = new Reply<>();

        api.resetAttempts(WORKFLOW, FACE, reset);
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_CANCELED, null);

        assertEquals("LIVENESS_IN_PROGRESS", reset.code());
        assertEquals(1, check.calls);
        assertEquals("CANCELLED", check.value.getFinalState());
    }

    @Test
    public void tcAnd02_passWithoutDiagnostics_mapsWithoutAScore() {
        LivenessPigeon.LivenessResultDto dto = mapped(passed(), false);

        assertEquals("PASSED", dto.getFinalState());
        assertEquals("LIVE", dto.getDecision());
        assertNull(dto.getErrorCode());
        assertEquals("liveness.feedback.success", dto.getMessageKey());
        assertNull(dto.getPassiveScore());
    }

    @Test
    public void tcAnd02_passWithDiagnostics_mapsTheScore() {
        assertEquals(0.93, mapped(passed(), true).getPassiveScore(), 1e-9);
    }

    @Test
    public void tcAnd02_attack_mapsToTheGenericFailure() {
        LivenessPigeon.LivenessResultDto dto = mapped(LivenessResult.builder(SessionState.FAILED,
                Decision.ATTACK_SUSPECTED).errorCode(LivenessErrorCode.PAD_FAILED).build(), false);

        assertEquals("ATTACK_SUSPECTED", dto.getDecision());
        assertEquals("LIV-008", dto.getErrorCode());
        assertEquals("liveness.fail.generic", dto.getMessageKey());
    }

    @Test
    public void tcAnd02_engineError_mapsToTheEngineMessage() {
        LivenessPigeon.LivenessResultDto dto = mapped(LivenessResult.builder(SessionState.ERROR,
                Decision.INCONCLUSIVE).errorCode(LivenessErrorCode.ENGINE_ERROR).build(), false);

        assertEquals("ERROR", dto.getFinalState());
        assertEquals("LIV-013", dto.getErrorCode());
        assertEquals("liveness.error.engine", dto.getMessageKey());
        assertEquals(Boolean.TRUE, dto.getRetryAllowed());
    }

    /** Runs one check whose activity returns [result], and gives the DTO Dart receives. */
    private LivenessPigeon.LivenessResultDto mapped(LivenessResult result, boolean diagnostics) {
        LivenessApiImpl api = api("enabled=true");
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();
        api.startLiveness(WORKFLOW, FACE, reply);
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK, LivenessContract.resultIntent(
                LivenessSessionStore.shared().complete(WORKFLOW, FACE, result, diagnostics)));
        return reply.value;
    }

    private static LivenessResult passed() {
        return LivenessResult.builder(SessionState.PASSED, Decision.LIVE).passiveScore(0.93).build();
    }

    private LivenessApiImpl api(String overrides) {
        LivenessApiImpl api = new LivenessApiImpl(globalParams, localConfig, overrides);
        api.setCallbackActivity(activity);
        return api;
    }

    private static LivenessResult failedChallenge() {
        return LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_FAILED)
                .challenges(Arrays.asList(
                        new ChallengeOutcome(ChallengeType.SMILE, ChallengeProgress.PASSED, 900L),
                        new ChallengeOutcome(ChallengeType.TURN_LEFT, ChallengeProgress.FAILED, 2000L)))
                .build();
    }

    private static final class Reply<T> implements LivenessPigeon.Result<T> {
        private T value;
        private Throwable error;
        private int calls;

        @Override
        public void success(T result) {
            value = result;
            calls++;
        }

        @Override
        public void error(Throwable throwable) {
            error = throwable;
            calls++;
        }

        String code() {
            assertEquals("expected exactly one reply", 1, calls);
            return ((LivenessPigeon.FlutterError) error).code;
        }
    }
}
