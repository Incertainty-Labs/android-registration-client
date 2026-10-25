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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.service.FaceAuthService;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
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

        api("enabled=true").startLiveness(WORKFLOW, FACE, reply);

        LivenessContract.Request request = LivenessContract.parseRequest(
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent);
        assertEquals("true", request.config().get(PREFIX + "enabled"));
    }

    @Test
    public void tcEng_onlyTheTestScreenCanForceTheStub() {
        synced.put(PREFIX + "stub.force", "PASS_ACTIVE");

        api("enabled=true;stub.force=PAD").startLiveness(WORKFLOW, FACE, new Reply<>());

        LivenessContract.Request request = LivenessContract.parseRequest(
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent);
        assertNull(request.config().get(PREFIX + "stub.force"));
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
        LivenessApiImpl api = new LivenessApiImpl(globalParams, localConfig, "enabled=true", false);
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
    public void tcAnd10_faceAuth_unknownWorkflow_isRejected() {
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        faceAuthApi().authenticateWithFace(OPERATOR, "OPERATOR_AUTH", reply);

        assertEquals("LIVENESS_BAD_REQUEST", reply.code());
    }

    @Test
    public void tcAnd10_faceAuth_liveFaceThatMatches_isAuthenticated() {
        LivenessApiImpl api = faceAuthApi();
        passLiveness(api, PACKET_AUTH);
        authCapture.hold(Collections.singletonList(new BiometricsDto()));
        when(faceAuth.authenticate(eq(OPERATOR), anyList())).thenReturn(FaceAuthService.Outcome.MATCHED);
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        api.authenticateWithFace(OPERATOR, PACKET_AUTH, reply);

        assertEquals(Boolean.TRUE, reply.value.getAuthenticated());
        assertNull(reply.value.getErrorCode());
        assertTrue("the capture is used once", authCapture.take().isEmpty());
        verify(audit).audit(eq(AuditEvent.CREATE_PACKET_AUTH), eq(Components.REGISTRATION), contains(OPERATOR));
    }

    @Test
    public void tcAnd11_faceAuth_otherFace_isNotAuthenticated() {
        LivenessPigeon.AuthResultDto dto = authenticateWith(FaceAuthService.Outcome.NOT_MATCHED);

        assertEquals(Boolean.FALSE, dto.getAuthenticated());
        assertEquals("FACE_NOT_MATCHED", dto.getErrorCode());
        assertEquals("liveness.auth.no_match", dto.getMessageKey());
        verify(audit).audit(eq(AuditEvent.CREATE_PACKET_AUTH_FAILED), eq(Components.REGISTRATION), anyString());
    }

    @Test
    public void tcAnd10_faceAuth_userWithoutTemplate_isAnEnrolmentProblem() {
        LivenessPigeon.AuthResultDto dto = authenticateWith(FaceAuthService.Outcome.NO_TEMPLATE);

        assertEquals("FACE_NO_TEMPLATE", dto.getErrorCode());
        assertEquals("liveness.auth.no_template", dto.getMessageKey());
    }

    @Test
    public void tcAnd10_faceAuth_noMatchProvider_isUnavailable() {
        LivenessPigeon.AuthResultDto dto = authenticateWith(FaceAuthService.Outcome.NO_PROVIDER);

        assertEquals("FACE_NO_PROVIDER", dto.getErrorCode());
        assertEquals("liveness.auth.unavailable", dto.getMessageKey());
    }

    @Test
    public void tcAnd10_faceAuth_withoutALivenessPass_neverMatches() {
        LivenessApiImpl api = faceAuthApi();
        authCapture.hold(Collections.singletonList(new BiometricsDto()));
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        api.authenticateWithFace(OPERATOR, PACKET_AUTH, reply);

        assertEquals(Boolean.FALSE, reply.value.getAuthenticated());
        assertEquals("LIVENESS_REQUIRED", reply.value.getErrorCode());
        verify(faceAuth, never()).authenticate(anyString(), anyList());
    }

    @Test
    public void tcAnd10_faceAuth_aPassForAnotherWorkflow_doesNotCount() {
        LivenessApiImpl api = faceAuthApi();
        passLiveness(api, "SUPERVISOR_EOD_AUTH");
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        api.authenticateWithFace(OPERATOR, PACKET_AUTH, reply);

        assertEquals("LIVENESS_REQUIRED", reply.value.getErrorCode());
    }

    @Test
    public void tcAnd10_faceAuth_livenessOff_failsClosed() {
        LivenessApiImpl api = new LivenessApiImpl(globalParams, localConfig, null, true);
        api.setFaceAuth(faceAuth, authCapture, audit);
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        api.authenticateWithFace(OPERATOR, PACKET_AUTH, reply);

        assertEquals(Boolean.FALSE, reply.value.getAuthenticated());
        assertEquals("LIVENESS_DISABLED", reply.value.getErrorCode());
    }

    @Test
    public void tcAnd10_seed_isRefusedWithoutTheTestScreen() {
        authCapture.hold(Collections.singletonList(new BiometricsDto()));
        Reply<Void> reply = new Reply<>();

        LivenessApiImpl fieldBuild = api("enabled=true");
        fieldBuild.setFaceAuth(faceAuth, authCapture, audit);

        fieldBuild.seedFaceTemplate(OPERATOR, reply);

        assertEquals("TEST_OVERRIDES_NOT_ALLOWED", reply.code());
        verify(faceAuth, never()).seedTemplate(anyString(), anyList());
    }

    @Test
    public void tcAnd10_seed_savesTheHeldCaptureForTheUser() {
        LivenessApiImpl api = api("enabled=true", true);
        api.setFaceAuth(faceAuth, authCapture, audit);
        List<BiometricsDto> face = Collections.singletonList(new BiometricsDto());
        authCapture.hold(face);
        when(faceAuth.seedTemplate(OPERATOR, face)).thenReturn(FaceAuthService.SeedOutcome.SAVED);
        Reply<Void> reply = new Reply<>();

        api.seedFaceTemplate(OPERATOR, reply);

        assertEquals(1, reply.calls);
        assertNull(reply.error);
        assertTrue(authCapture.take().isEmpty());
    }

    @Test
    public void tcAnd10_seed_neverReplacesAnOnboardedTemplate() {
        LivenessApiImpl api = api("enabled=true", true);
        api.setFaceAuth(faceAuth, authCapture, audit);
        authCapture.hold(Collections.singletonList(new BiometricsDto()));
        when(faceAuth.seedTemplate(eq(OPERATOR), anyList())).thenReturn(FaceAuthService.SeedOutcome.ENROLLED);
        Reply<Void> reply = new Reply<>();

        api.seedFaceTemplate(OPERATOR, reply);

        assertEquals("FACE_ALREADY_ENROLLED", reply.code());
    }

    @Test
    public void tcAnd10_faceAuth_fieldBuild_isNotOffered() {
        when(faceAuth.isAvailable()).thenReturn(true);
        LivenessApiImpl fieldBuild = api("enabled=true");
        fieldBuild.setFaceAuth(faceAuth, authCapture, audit);
        Reply<Map<String, String>> reply = new Reply<>();

        fieldBuild.getLivenessConfig(PACKET_AUTH, reply);

        assertEquals("false", reply.value.get("faceAuthAvailable"));
    }

    @Test
    public void tcAnd10_faceAuth_fieldBuild_isRefused() {
        LivenessApiImpl fieldBuild = api("enabled=true");
        fieldBuild.setFaceAuth(faceAuth, authCapture, audit);
        passLiveness(fieldBuild, PACKET_AUTH);
        authCapture.hold(Collections.singletonList(new BiometricsDto()));
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();

        fieldBuild.authenticateWithFace(OPERATOR, PACKET_AUTH, reply);

        assertEquals(Boolean.FALSE, reply.value.getAuthenticated());
        assertEquals("FACE_AUTH_NOT_ALLOWED", reply.value.getErrorCode());
        verify(faceAuth, never()).authenticate(anyString(), anyList());
    }

    @Test
    public void tcAnd10_seedWithoutACapture_failsClearly() {
        LivenessApiImpl api = api("enabled=true", true);
        api.setFaceAuth(faceAuth, authCapture, audit);
        Reply<Void> reply = new Reply<>();

        api.seedFaceTemplate(OPERATOR, reply);

        assertEquals("FACE_NO_CAPTURE", reply.code());
    }

    @Test
    public void tcAnd18_start_isAudited() {
        faceAuthApi().startLiveness(WORKFLOW, FACE, new Reply<>());

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_SESSION_STARTED), anyString(), anyString(),
                eq(WORKFLOW), eq("HYBRID"), eq("camerax:front"), eq("L0-camera"), eq("false"), eq("true"));
    }

    @Test
    public void tcAnd18_result_isAuditedFromTheFullResult() {
        LivenessApiImpl api = faceAuthApi();
        api.startLiveness(WORKFLOW, FACE, new Reply<>());

        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK, LivenessContract.resultIntent(
                LivenessSessionStore.shared().complete(WORKFLOW, FACE, passed(), false)));

        verify(audit).auditWithArguments(eq(AuditEvent.LIVENESS_PASSED), anyString(), anyString(),
                eq(WORKFLOW), eq("0.93"), anyString(), anyString(), eq("none"));
    }

    @Test
    public void tcAnd10_operatorAuthCapture_isLeftToTheMatch() {
        assertTrue(api("enabled=true").faceCaptureAllowed("operatorAuth"));
    }

    @Test
    public void tcAnd10_config_saysWhetherFaceAuthIsAvailable() {
        when(faceAuth.isAvailable()).thenReturn(true);
        Reply<Map<String, String>> reply = new Reply<>();

        faceAuthApi().getLivenessConfig(PACKET_AUTH, reply);

        assertEquals("true", reply.value.get("faceAuthAvailable"));
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

    @Test
    public void tcAnd05_livenessOff_allowsTheFaceCapture() {
        assertTrue(api(null).faceCaptureAllowed(FACE));
    }

    @Test
    public void tcAnd06_noPass_refusesTheFaceCapture() {
        assertFalse(api("enabled=true").faceCaptureAllowed(FACE));
    }

    @Test
    public void tcAnd05_aPass_allowsOneFaceCapture() {
        LivenessApiImpl api = api("enabled=true");
        mappedWith(api, passed());

        assertTrue(api.faceCaptureAllowed(FACE));
        assertFalse(api.faceCaptureAllowed(FACE));
    }

    @Test
    public void tcAnd09_operatorBiometrics_needAnOnboardingPass() {
        LivenessApiImpl api = api("enabled=true");
        mappedWith(api, passed());

        assertFalse(api.faceCaptureAllowed("operatorBiometrics"));
    }

    @Test
    public void tcAnd05_anExceptionPhotoPass_allowsOneExceptionPhoto() {
        LivenessApiImpl api = api("enabled=true");
        checkedUnder(api, EXCEPTION_PHOTO, passed());

        assertTrue(api.exceptionPhotoAllowed(FACE));
        assertFalse(api.exceptionPhotoAllowed(FACE));
    }

    @Test
    public void tcAnd06_aFacePass_doesNotCoverTheExceptionPhoto() {
        LivenessApiImpl api = api("enabled=true");
        mappedWith(api, passed());

        assertFalse(api.exceptionPhotoAllowed(FACE));
        assertTrue(api.faceCaptureAllowed(FACE));
    }

    @Test
    public void tcAnd05_livenessOff_allowsTheExceptionPhoto() {
        assertTrue(api(null).exceptionPhotoAllowed(FACE));
    }

    private static final String EXCEPTION_PHOTO = "RESIDENT_EXCEPTION_PHOTO";

    /** Runs one check under [workflow] for the resident's field, whose activity returns [result]. */
    private void checkedUnder(LivenessApiImpl api, String workflow, LivenessResult result) {
        Reply<LivenessPigeon.LivenessResultDto> reply = new Reply<>();
        api.startLiveness(workflow, FACE, reply);
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK, LivenessContract.resultIntent(
                LivenessSessionStore.shared().complete(workflow, FACE, result, false)));
    }

    /** Runs one check whose activity returns [result], and gives the DTO Dart receives. */
    private LivenessPigeon.LivenessResultDto mapped(LivenessResult result, boolean diagnostics) {
        return mappedWith(api("enabled=true"), result, diagnostics);
    }

    private LivenessPigeon.LivenessResultDto mappedWith(LivenessApiImpl api, LivenessResult result) {
        return mappedWith(api, result, false);
    }

    private LivenessPigeon.LivenessResultDto mappedWith(LivenessApiImpl api, LivenessResult result,
                                                        boolean diagnostics) {
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
        return api(overrides, false);
    }

    private static final String OPERATOR = "110011";
    private static final String PACKET_AUTH = "OPERATOR_PACKET_AUTH";

    private final FaceAuthService faceAuth = mock(FaceAuthService.class);
    private final AuditManagerService audit = mock(AuditManagerService.class);
    private final OperatorAuthCapture authCapture = new OperatorAuthCapture();

    private LivenessApiImpl faceAuthApi() {
        LivenessApiImpl api = api("enabled=true", true);
        api.setFaceAuth(faceAuth, authCapture, audit);
        return api;
    }

    /** A liveness check for [workflow] on the operatorAuth field passes. */
    private void passLiveness(LivenessApiImpl api, String workflow) {
        api.startLiveness(workflow, OperatorAuthCapture.FIELD_ID, new Reply<>());
        Shadows.shadowOf(activity).getNextStartedActivityForResult();
        api.onActivityResult(LivenessContract.REQUEST_CODE, Activity.RESULT_OK, LivenessContract.resultIntent(
                LivenessSessionStore.shared().complete(workflow, OperatorAuthCapture.FIELD_ID, passed(), false)));
    }

    private LivenessPigeon.AuthResultDto authenticateWith(FaceAuthService.Outcome outcome) {
        LivenessApiImpl api = faceAuthApi();
        passLiveness(api, PACKET_AUTH);
        authCapture.hold(Collections.singletonList(new BiometricsDto()));
        when(faceAuth.authenticate(eq(OPERATOR), anyList())).thenReturn(outcome);
        Reply<LivenessPigeon.AuthResultDto> reply = new Reply<>();
        api.authenticateWithFace(OPERATOR, PACKET_AUTH, reply);
        return reply.value;
    }

    private LivenessApiImpl api(String overrides, boolean testScreen) {
        LivenessApiImpl api = new LivenessApiImpl(globalParams, localConfig, overrides, testScreen);
        api.setCallbackActivity(activity);
        return api;
    }

    @Test
    public void tcAnd_b21_testOverrides_areRefusedWithoutTheTestScreen() {
        Reply<Void> reply = new Reply<>();

        api("enabled=true").setTestOverrides("stub.force=PAD", reply);

        assertEquals("TEST_OVERRIDES_NOT_ALLOWED", reply.code());
    }

    @Test
    public void tcAnd_b21_testOverrides_reachTheNextSession() {
        LivenessApiImpl api = api("enabled=true;stub.force=PASS_ACTIVE", true);
        Reply<Void> set = new Reply<>();

        api.setTestOverrides("stub.force=PAD", set);
        api.startLiveness(WORKFLOW, FACE, new Reply<>());

        assertEquals(1, set.calls);
        LivenessContract.Request request = LivenessContract.parseRequest(
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent);
        assertEquals("PAD", request.config().get(PREFIX + "stub.force"));
        assertEquals("true", request.config().get(PREFIX + "enabled"));
    }

    @Test
    public void tcAnd_b21_emptyTestOverrides_clearThem() {
        LivenessApiImpl api = api("enabled=true", true);
        api.setTestOverrides("stub.force=PAD", new Reply<>());

        api.setTestOverrides("", new Reply<>());
        api.startLiveness(WORKFLOW, FACE, new Reply<>());

        LivenessContract.Request request = LivenessContract.parseRequest(
                Shadows.shadowOf(activity).getNextStartedActivityForResult().intent);
        assertNull(request.config().get(PREFIX + "stub.force"));
    }

    @Test
    public void tcAnd_b21_config_saysWhetherTheTestScreenIsAllowed() {
        Reply<Map<String, String>> off = new Reply<>();
        Reply<Map<String, String>> on = new Reply<>();

        api("enabled=true").getLivenessConfig(WORKFLOW, off);
        api("enabled=true", true).getLivenessConfig(WORKFLOW, on);

        assertEquals("false", off.value.get("testScreen"));
        assertEquals("true", on.value.get("testScreen"));
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
