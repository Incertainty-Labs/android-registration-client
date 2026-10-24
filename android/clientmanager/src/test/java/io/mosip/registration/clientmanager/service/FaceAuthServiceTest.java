package io.mosip.registration.clientmanager.service;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.Match;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.model.Decision;
import io.mosip.kernel.biometrics.model.MatchDecision;
import io.mosip.kernel.biometrics.model.Response;
import io.mosip.kernel.biometrics.spi.IBioApiV2;
import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.entity.UserBiometric;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.util.BioSdkProviderFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-09: 1:1 operator face match through the BioSDK against the user's onboarding face templates (TC-AND-10). */
public class FaceAuthServiceTest {

    private static final String USER = "110011";
    private static final byte[] PROBE = {1, 2, 3};

    private final UserBiometricRepository templates = mock(UserBiometricRepository.class);
    private final BioSdkProviderFactory sdks = mock(BioSdkProviderFactory.class);
    private final IBioApiV2 sdk = mock(IBioApiV2.class);
    private FaceAuthService service;

    @Before
    public void setUp() {
        when(sdks.getProviderForMatch(Modality.FACE)).thenReturn(sdk);
        when(templates.findFaceTemplates(USER)).thenReturn(Collections.singletonList(template(new byte[]{9})));
        service = new FaceAuthService(templates, sdks);
    }

    @Test
    public void tcAnd10_matchingFace_isAuthenticated() throws Exception {
        answer(Match.MATCHED);

        assertEquals(FaceAuthService.Outcome.MATCHED, service.authenticate(USER, capture()));
    }

    @Test
    public void tcAnd11_otherFace_isNotAuthenticated() throws Exception {
        answer(Match.NOT_MATCHED);

        assertEquals(FaceAuthService.Outcome.NOT_MATCHED, service.authenticate(USER, capture()));
    }

    @Test
    public void tcAnd10_allTheUsersTemplates_formOneGalleryRecord() throws Exception {
        when(templates.findFaceTemplates(USER)).thenReturn(java.util.Arrays.asList(
                template(new byte[]{7}), template(new byte[]{8})));
        answer(Match.MATCHED);
        ArgumentCaptor<BiometricRecord[]> gallery = ArgumentCaptor.forClass(BiometricRecord[].class);
        ArgumentCaptor<BiometricRecord> probe = ArgumentCaptor.forClass(BiometricRecord.class);

        service.authenticate(USER, capture());

        verify(sdk).match(probe.capture(), gallery.capture(), anyList(), anyMap());
        assertEquals(1, gallery.getValue().length);
        assertEquals(2, gallery.getValue()[0].getSegments().size());
        assertArrayEquals(PROBE, probe.getValue().getSegments().get(0).getBdb());
    }

    @Test
    public void tcAnd10_userWithoutAFaceTemplate_isAnEnrolmentProblem() {
        when(templates.findFaceTemplates(USER)).thenReturn(Collections.emptyList());

        assertEquals(FaceAuthService.Outcome.NO_TEMPLATE, service.authenticate(USER, capture()));
        verify(sdk, never()).match(any(), any(), anyList(), anyMap());
    }

    @Test
    public void tcAnd10_noFaceMatchProvider_failsClosed() {
        when(sdks.getProviderForMatch(Modality.FACE)).thenReturn(null);

        assertEquals(FaceAuthService.Outcome.NO_PROVIDER, service.authenticate(USER, capture()));
    }

    @Test
    public void tcAnd10_sdkFailure_failsClosed() {
        when(sdk.match(any(), any(), anyList(), anyMap())).thenThrow(new IllegalStateException("SDK down"));

        assertEquals(FaceAuthService.Outcome.NO_PROVIDER, service.authenticate(USER, capture()));
    }

    @Test
    public void tcAnd10_noCapture_isNotAMatch() {
        assertEquals(FaceAuthService.Outcome.NO_CAPTURE, service.authenticate(USER, Collections.emptyList()));
        assertEquals(FaceAuthService.Outcome.NO_CAPTURE, service.authenticate(USER, null));
    }

    @Test
    public void tcAnd10_isAvailable_onlyWithAFaceMatchProvider() {
        assertEquals(true, service.isAvailable());

        when(sdks.getProviderForMatch(Modality.FACE)).thenReturn(null);
        assertEquals(false, service.isAvailable());
    }

    private void answer(Match match) {
        Decision decision = new Decision();
        decision.setMatch(match);
        Map<BiometricType, Decision> decisions = new EnumMap<>(BiometricType.class);
        decisions.put(BiometricType.FACE, decision);
        MatchDecision matchDecision = new MatchDecision(0);
        matchDecision.setDecisions(decisions);
        Response<MatchDecision[]> response = new Response<>();
        response.setStatusCode(200);
        response.setResponse(new MatchDecision[]{matchDecision});
        when(sdk.match(any(), any(), anyList(), anyMap())).thenReturn(response);
    }

    private static List<BiometricsDto> capture() {
        BiometricsDto face = new BiometricsDto();
        face.setModality("Face");
        face.setBioSubType("face");
        face.setBioValue(Base64.getUrlEncoder().withoutPadding().encodeToString(PROBE));
        face.setQualityScore(90f);
        return Collections.singletonList(face);
    }

    private static UserBiometric template(byte[] iso) {
        UserBiometric template = new UserBiometric();
        template.setUsrId(USER);
        template.setBioTypeCode("Face");
        template.setBioAttributeCode("face");
        template.setBioTemplate(iso);
        template.setQualityScore(80);
        return template;
    }
}
