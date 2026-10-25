package io.mosip.registration.clientmanager.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.dto.sbi.CaptureDto;
import io.mosip.registration.clientmanager.dto.sbi.CaptureFreshnessTicket;
import io.mosip.registration.clientmanager.dto.sbi.CaptureRequest;
import io.mosip.registration.clientmanager.dto.sbi.CaptureRespDetail;
import io.mosip.registration.clientmanager.dto.sbi.CaptureResponse;
import io.mosip.registration.clientmanager.exception.CaptureNotFreshException;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.clientmanager.util.BioSdkProviderFactory;
import io.mosip.registration.keymanager.spi.ClientCryptoManagerService;

/**
 * B-07 capture freshness: the RCAPTURE request carries the session nonce as its transactionId, and the device-signed
 * response must echo it and be signed close to when liveness passed (or to when the request was sent, with liveness
 * off). Anything else is LIV-014 with a generic message.
 */
public class Biometrics095ServiceFreshnessTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final long PASSED_AT = Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();
    private static final long MAX_AGE_MS = 60_000L;

    private Biometrics095Service service;

    @Before
    public void setUp() throws Exception {
        Context context = mock(Context.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(mock(SharedPreferences.class));
        when(context.getString(anyInt())).thenReturn("app");
        service = spy(new Biometrics095Service(context, new ObjectMapper(), mock(AuditManagerService.class),
                mock(GlobalParamRepository.class), mock(ClientCryptoManagerService.class),
                mock(UserBiometricRepository.class), mock(BioSdkProviderFactory.class)));
        doNothing().when(service).validateJWTResponse(anyString(), anyString());
        doNothing().when(service).validateResponseTimestamp(anyString());
    }

    @Test
    public void tcFresh_requestCarriesTheGivenNonceAsItsTransactionId() {
        CaptureRequest request = service.getRCaptureRequest(Modality.FACE, "dev", Collections.emptyList(), NONCE);

        assertEquals(NONCE, request.getTransactionId());
    }

    @Test
    public void tcFresh_requestWithoutANonce_getsANewRandomNonce() {
        String first = service.getRCaptureRequest(Modality.FACE, "dev", Collections.emptyList()).getTransactionId();
        String second = service.getRCaptureRequest(Modality.FACE, "dev", Collections.emptyList()).getTransactionId();

        assertTrue(first, first.matches("[0-9a-f]{32}"));
        assertFalse(first.equals(second));
    }

    @Test
    public void tcFresh_captureThatEchoesTheNonceSoonAfterThePass_isAccepted() throws Exception {
        List<BiometricsDto> faces = handle(NONCE, "2026-10-25T10:00:05Z");

        assertEquals(1, faces.size());
    }

    @Test
    public void tcFresh_signedTimestampWithAnOffset_isReadAsThatInstant() throws Exception {
        assertEquals(1, handle(NONCE, "2026-10-25T15:30:05+05:30").size());
        assertEquals(PASSED_AT + 5_000L, Biometrics095Service.signedEpochMs("2026-10-25T15:30:05+05:30"));
        assertEquals(PASSED_AT + 5_000L, Biometrics095Service.signedEpochMs("2026-10-25T10:00:05"));
    }

    @Test
    public void tcFresh_captureSignedTooLongAfterThePass_failsWithLiv014AndAGenericMessage() {
        CaptureNotFreshException e = assertThrows(CaptureNotFreshException.class,
                () -> handle(NONCE, "2026-10-25T10:01:01Z"));

        assertEquals("REG-LIV-014", e.getErrorCode());
        assertEquals(CaptureNotFreshException.MESSAGE, e.getErrorText());
        assertTrue(e.reason(), e.reason().contains("from the reference"));
    }

    @Test
    public void tcFresh_captureThatDoesNotEchoTheNonce_failsWithLiv014() {
        CaptureNotFreshException e = assertThrows(CaptureNotFreshException.class,
                () -> handle("ffffffffffffffffffffffffffffffff", "2026-10-25T10:00:05Z"));

        assertEquals("REG-LIV-014", e.getErrorCode());
        assertTrue(e.reason(), e.reason().contains("nonce"));
    }

    @Test
    public void tcFresh_unreadableSignedTimestamp_failsWithLiv014() {
        CaptureNotFreshException e = assertThrows(CaptureNotFreshException.class,
                () -> handle(NONCE, "yesterday"));

        assertEquals("REG-LIV-014", e.getErrorCode());
    }

    private List<BiometricsDto> handle(String signedTransactionId, String signedTimestamp) throws Exception {
        CaptureFreshnessTicket ticket = new CaptureFreshnessTicket(NONCE, PASSED_AT, MAX_AGE_MS,
                "RESIDENT_REGISTRATION", "individualBiometrics");
        return service.handleRCaptureResponse(Modality.FACE, response(signedTransactionId, signedTimestamp),
                Collections.emptyList(), ticket, false);
    }

    private static InputStream response(String transactionId, String timestamp) throws Exception {
        CaptureDto signed = new CaptureDto();
        signed.setTransactionId(transactionId);
        signed.setTimestamp(timestamp);
        signed.setBioType("Face");
        signed.setBioSubType("");
        signed.setBioValue("aW1hZ2U");
        signed.setQualityScore(90f);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new ObjectMapper().writeValueAsBytes(signed));
        CaptureRespDetail detail = new CaptureRespDetail();
        detail.setData("header." + payload + ".sig");
        detail.setSpecVersion("0.9.5");
        CaptureResponse response = new CaptureResponse();
        response.setBiometrics(Collections.singletonList(detail));
        return new ByteArrayInputStream(new ObjectMapper().writeValueAsBytes(response));
    }
}
