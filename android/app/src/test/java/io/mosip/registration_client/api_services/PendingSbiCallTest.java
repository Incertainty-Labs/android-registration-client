package io.mosip.registration_client.api_services;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import io.mosip.registration_client.model.BiometricsPigeon;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class PendingSbiCallTest {

    @SuppressWarnings("unchecked")
    private final BiometricsPigeon.Result<String> result = mock(BiometricsPigeon.Result.class);

    @Test
    public void tcAnd14_withStartedCall_completeRepliesSuccess() {
        PendingSbiCall call = new PendingSbiCall();
        call.start(result);

        call.complete("Ok");

        verify(result).success("Ok");
    }

    @Test
    public void tcAnd14_withStartedCall_failRepliesFlutterErrorWithCodeAndMessage() {
        PendingSbiCall call = new PendingSbiCall();
        call.start(result);

        call.fail("REG-SBI-118", "Capture cancelled");

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(result).error(error.capture());
        BiometricsPigeon.FlutterError flutterError = (BiometricsPigeon.FlutterError) error.getValue();
        assertEquals("REG-SBI-118", flutterError.code);
        assertEquals("Capture cancelled", flutterError.getMessage());
    }

    @Test
    public void tcAnd14_afterFirstReply_laterRepliesAreDropped() {
        PendingSbiCall call = new PendingSbiCall();
        call.start(result);

        call.complete("Ok");
        call.fail("REG-SBI-115", "RCapture Failed! ");
        call.complete("Ok");

        verify(result, times(1)).success("Ok");
        verify(result, never()).error(any(Throwable.class));
    }
}
