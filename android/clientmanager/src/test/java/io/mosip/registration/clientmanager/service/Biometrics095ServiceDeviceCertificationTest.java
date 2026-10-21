package io.mosip.registration.clientmanager.service;

import android.content.Context;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.dto.sbi.InfoResponse;
import io.mosip.registration.clientmanager.exception.BiometricsServiceException;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.clientmanager.util.DeviceCertificationPolicy;
import io.mosip.registration.keymanager.spi.ClientCryptoManagerService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-08: the device info step accepts the SBI certification levels the liveness config allows (TC-AND-03). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class Biometrics095ServiceDeviceCertificationTest {

    private static final String DEVICE_JWT = "device.info.jwt";
    private static final String DIGITAL_ID_JWT = "digital.id.jwt";

    private final GlobalParamRepository globalParams = mock(GlobalParamRepository.class);
    private final AuditManagerService audit = mock(AuditManagerService.class);
    private Biometrics095Service service;

    @Before
    public void setUp() throws Exception {
        // The service names its SharedPreferences after R.string.app_name, which a library unit test cannot resolve
        Context context = spy(RuntimeEnvironment.getApplication());
        doReturn("RegistrationClient").when(context).getString(anyInt());
        service = spy(new Biometrics095Service(context, new ObjectMapper(), audit, globalParams,
                mock(ClientCryptoManagerService.class), mock(UserBiometricRepository.class), null));
        doNothing().when(service).validateJWTResponse(anyString(), anyString());
        doNothing().when(service).addBioDevice(any(), any(), any(), any(), any());
        doReturn(base64Url("{\"serialNo\":\"SN-1\",\"make\":\"mock\",\"model\":\"face\",\"type\":\"Face\"}"))
                .when(service).getJWTPayLoad(DIGITAL_ID_JWT);
    }

    @Test
    public void tcAnd03_withL1DeviceAndNoConfig_acceptsTheDevice() throws Exception {
        String[] result = service.handleDeviceInfoResponse(Modality.FACE, deviceInfoResponse("L1"));

        assertEquals("io.sbi.face", result[0]);
        assertEquals("SN-1", result[1]);
    }

    @Test
    public void tcAnd03_withOnlyL1Configured_refusesAnL0DeviceAndAuditsItsLevel() throws Exception {
        when(globalParams.getCachedStringGlobalParam(DeviceCertificationPolicy.CONFIG_KEY)).thenReturn("L1");

        BiometricsServiceException refused = assertThrows(BiometricsServiceException.class,
                () -> service.handleDeviceInfoResponse(Modality.FACE, deviceInfoResponse("L0")));

        assertEquals("REG-SBI-113", refused.getErrorCode());
        verify(audit).audit(eq(AuditEvent.DEVICE_INFO_PARSE_FAILED), eq(Components.REGISTRATION),
                contains("certification L0"));
    }

    /** A signed device info response whose payload reports the given certification level. */
    private byte[] deviceInfoResponse(String certification) throws Exception {
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("specVersion", new String[] {"0.9.5"});
        device.put("deviceStatus", "Ready");
        device.put("certification", certification);
        device.put("purpose", "Registration");
        device.put("callbackId", "io.sbi.face.Info");
        device.put("deviceCode", "DC-1");
        device.put("digitalId", DIGITAL_ID_JWT);
        doReturn(base64Url(new ObjectMapper().writeValueAsString(device))).when(service).getJWTPayLoad(DEVICE_JWT);
        InfoResponse info = new InfoResponse();
        info.setDeviceInfo(DEVICE_JWT);
        return new ObjectMapper().writeValueAsBytes(Collections.singletonList(info));
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
