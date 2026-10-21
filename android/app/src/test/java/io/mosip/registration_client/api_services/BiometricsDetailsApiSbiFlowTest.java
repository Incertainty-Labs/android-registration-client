package io.mosip.registration_client.api_services;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.dto.registration.RegistrationDto;
import io.mosip.registration.clientmanager.dto.sbi.CaptureRequest;
import io.mosip.registration.clientmanager.exception.BiometricsServiceException;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.service.Biometrics095Service;
import io.mosip.registration.clientmanager.service.UserOnboardService;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.clientmanager.spi.RegistrationService;
import io.mosip.registration_client.MainActivity;
import io.mosip.registration_client.model.BiometricsPigeon;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-18: every way an SBI capture flow ends completes the waiting Dart call (TC-AND-14). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BiometricsDetailsApiSbiFlowTest {

    private static final int DISCOVER = 1;
    private static final int DEVICE_INFO = 2;
    private static final int CAPTURE = 3;

    private final MainActivity activity = mock(MainActivity.class);
    private final Biometrics095Service biometricsService = mock(Biometrics095Service.class);
    private final RegistrationService registrationService = mock(RegistrationService.class);
    @SuppressWarnings("unchecked")
    private final BiometricsPigeon.Result<String> result = mock(BiometricsPigeon.Result.class);
    private BiometricsDetailsApi api;

    @Before
    public void setUp() {
        when(activity.getPackageManager()).thenReturn(mock(PackageManager.class));
        // master stores the capture request's transactionId on the registration before starting RCAPTURE
        CaptureRequest captureRequest = new CaptureRequest();
        captureRequest.setTransactionId("TXN-1");
        when(biometricsService.getRCaptureRequest(any(), any(), any())).thenReturn(captureRequest);
        api = new BiometricsDetailsApi(mock(AuditManagerService.class), new ObjectMapper(), biometricsService,
                registrationService, mock(GlobalParamRepository.class), mock(UserOnboardService.class));
        api.setCallbackActivity(activity);
    }

    @Test
    public void tcAnd14_whenCaptureIsCancelled_failsWithCaptureCancelled() {
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);

        api.onSbiActivityResult(CAPTURE, Activity.RESULT_CANCELED, null);

        assertEquals("REG-SBI-118", errorCode());
    }

    @Test
    public void tcAnd16_whenNoSbiAppIsInstalled_failsWithDeviceNotFound() {
        doThrow(new ActivityNotFoundException("no io.sbi.device app"))
                .when(activity).startActivityForResult(any(Intent.class), eq(DISCOVER));

        api.invokeDiscoverSbi("individualBiometrics", "Face", result);

        assertEquals("REG-SBI-104", errorCode());
    }

    @Test
    public void tcAnd14_whenDiscoveryResponseIsInvalid_failsWithTheServiceErrorCode() throws Exception {
        when(biometricsService.handleDiscoveryResponse(any(), any()))
                .thenThrow(new BiometricsServiceException("REG-SBI-114", "Discovery Failed! Invalid response"));
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);

        api.onSbiActivityResult(DISCOVER, Activity.RESULT_OK, sbiResponse());

        assertEquals("REG-SBI-114", errorCode());
    }

    @Test
    public void tcAnd14_whenDeviceInfoResponseIsInvalid_failsWithTheServiceErrorCode() throws Exception {
        when(biometricsService.handleDiscoveryResponse(any(), any())).thenReturn("io.sbi.face");
        when(biometricsService.handleDeviceInfoResponse(any(), any()))
                .thenThrow(new BiometricsServiceException("REG-SBI-113", "Device Info Failed! Invalid response"));
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);
        api.onSbiActivityResult(DISCOVER, Activity.RESULT_OK, sbiResponse());

        api.onSbiActivityResult(DEVICE_INFO, Activity.RESULT_OK, sbiResponse());

        assertEquals("REG-SBI-113", errorCode());
    }

    @Test
    public void tcAnd14_whenCaptureReturnsNoBiometrics_failsWithRCaptureFailed() throws Exception {
        startCaptureFlow();
        when(biometricsService.handleRCaptureResponse(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new ArrayList<>());

        api.onSbiActivityResult(CAPTURE, Activity.RESULT_OK, captureResponse());

        assertEquals("REG-SBI-115", errorCode());
    }

    @Test
    public void tcAnd14_whenCaptureIsRejected_failsWithTheServiceErrorCode() throws Exception {
        startCaptureFlow();
        when(biometricsService.handleRCaptureResponse(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new BiometricsServiceException("REG-SBI-106", "Device response with invalid signature"));

        api.onSbiActivityResult(CAPTURE, Activity.RESULT_OK, captureResponse());

        assertEquals("REG-SBI-106", errorCode());
    }

    @Test
    public void tcAnd14_whenCaptureParsingFailsUnexpectedly_failsWithRCaptureFailed() throws Exception {
        startCaptureFlow();
        when(biometricsService.handleRCaptureResponse(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("unexpected"));

        api.onSbiActivityResult(CAPTURE, Activity.RESULT_OK, captureResponse());

        assertEquals("REG-SBI-115", errorCode());
    }

    @Test
    public void tcAnd05_whenCaptureSucceeds_repliesOkOnce() throws Exception {
        startCaptureFlow();
        when(biometricsService.handleRCaptureResponse(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new ArrayList<>(Collections.singletonList(mock(BiometricsDto.class))));

        api.onSbiActivityResult(CAPTURE, Activity.RESULT_OK, captureResponse());

        verify(result, times(1)).success("Ok");
        verify(result, never()).error(any(Throwable.class));
    }

    @Test
    public void tcAnd16_whenSbiInfoScreenIsMissing_failsWithDeviceNotFound() throws Exception {
        when(biometricsService.handleDiscoveryResponse(any(), any())).thenReturn("io.sbi.face");
        doThrow(new ActivityNotFoundException("no io.sbi.face.Info"))
                .when(activity).startActivityForResult(any(Intent.class), eq(DEVICE_INFO));
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);

        api.onSbiActivityResult(DISCOVER, Activity.RESULT_OK, sbiResponse());

        assertEquals("REG-SBI-104", errorCode());
    }

    @Test
    public void tcAnd16_whenSbiCaptureScreenIsMissing_failsWithDeviceNotFound() throws Exception {
        doThrow(new ActivityNotFoundException("no io.sbi.face.rCapture"))
                .when(activity).startActivityForResult(any(Intent.class), eq(CAPTURE));

        startCaptureFlow();

        assertEquals("REG-SBI-104", errorCode());
    }

    @Test
    public void tcAnd14_whenCaptureRequestCannotBeBuilt_failsWithSbiRequestFailed() throws Exception {
        when(biometricsService.getRCaptureRequest(any(), any(), any())).thenThrow(new IllegalStateException("bad"));

        startCaptureFlow();

        assertEquals("REG-SBI-105", errorCode());
    }

    @Test
    public void tcAnd14_whenDeviceInfoParsingFailsUnexpectedly_failsWithDeviceInfoInvalid() throws Exception {
        when(biometricsService.handleDiscoveryResponse(any(), any())).thenReturn("io.sbi.face");
        when(biometricsService.handleDeviceInfoResponse(any(), any())).thenThrow(new IllegalStateException("bad"));
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);
        api.onSbiActivityResult(DISCOVER, Activity.RESULT_OK, sbiResponse());

        api.onSbiActivityResult(DEVICE_INFO, Activity.RESULT_OK, sbiResponse());

        assertEquals("REG-SBI-113", errorCode());
    }

    @Test
    public void tcAnd14_whenDiscoveryCannotStart_failsWithSbiRequestFailed() {
        when(activity.getPackageManager()).thenReturn(null);

        api.invokeDiscoverSbi("individualBiometrics", "Face", result);

        assertEquals("REG-SBI-105", errorCode());
    }

    @Test
    public void tcAnd14_whenDeviceInfoHasNoSerial_failsWithDeviceNotFound() throws Exception {
        when(biometricsService.handleDiscoveryResponse(any(), any())).thenReturn("io.sbi.face");
        when(biometricsService.handleDeviceInfoResponse(any(), any()))
                .thenReturn(new String[] {"io.sbi.face", null, "Ready"});
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);
        api.onSbiActivityResult(DISCOVER, Activity.RESULT_OK, sbiResponse());

        api.onSbiActivityResult(DEVICE_INFO, Activity.RESULT_OK, sbiResponse());

        assertEquals("REG-SBI-104", errorCode());
    }

    @Test
    public void tcAnd14_whenScanIsTappedTwice_secondCallIsRejectedAndNoSecondDiscoveryStarts() {
        @SuppressWarnings("unchecked")
        BiometricsPigeon.Result<String> second = mock(BiometricsPigeon.Result.class);
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);

        api.invokeDiscoverSbi("individualBiometrics", "Face", second);

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(second).error(error.capture());
        assertEquals("REG-SBI-119", ((BiometricsPigeon.FlutterError) error.getValue()).code);
        verify(activity, times(1)).startActivityForResult(any(Intent.class), eq(DISCOVER));
        verify(result, never()).error(any(Throwable.class));
    }

    /** Discovery and device info succeed, so the next activity result is the capture's. */
    private void startCaptureFlow() throws Exception {
        when(registrationService.getRegistrationDto()).thenReturn(mock(RegistrationDto.class));
        when(activity.getContentResolver()).thenReturn(RuntimeEnvironment.getApplication().getContentResolver());
        when(biometricsService.handleDiscoveryResponse(any(), any())).thenReturn("io.sbi.face");
        when(biometricsService.handleDeviceInfoResponse(any(), any()))
                .thenReturn(new String[] {"io.sbi.face", "SN-1", "Ready"});
        api.invokeDiscoverSbi("individualBiometrics", "Face", result);
        api.onSbiActivityResult(DISCOVER, Activity.RESULT_OK, sbiResponse());
        api.onSbiActivityResult(DEVICE_INFO, Activity.RESULT_OK, sbiResponse());
    }

    /** RCAPTURE answers with a content URI to the response JSON; a temp file stands in for it. */
    private static Bundle captureResponse() throws Exception {
        File json = File.createTempFile("rcapture", ".json");
        json.deleteOnExit();
        Files.write(json.toPath(), "{\"biometrics\":[]}".getBytes());
        Bundle bundle = new Bundle();
        bundle.putParcelable("response", Uri.fromFile(json));
        return bundle;
    }

    private static Bundle sbiResponse() {
        Bundle bundle = new Bundle();
        bundle.putByteArray("response", "[]".getBytes());
        return bundle;
    }

    private String errorCode() {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(result).error(error.capture());
        return ((BiometricsPigeon.FlutterError) error.getValue()).code;
    }
}
