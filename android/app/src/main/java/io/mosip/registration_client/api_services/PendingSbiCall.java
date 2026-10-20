package io.mosip.registration_client.api_services;

import io.mosip.registration.clientmanager.constant.SBIError;
import io.mosip.registration_client.model.BiometricsPigeon;

/**
 * The Dart call waiting for an SBI capture flow (discover, info, capture). Every way the flow can end must complete
 * it, or the Dart side waits forever.
 */
final class PendingSbiCall {

    private BiometricsPigeon.Result<String> result;

    /**
     * Starts waiting for a flow. While another call is still waiting, the new one is failed at once with
     * {@code REG-SBI-119} and the waiting call keeps its place: its SBI flow is still running and will complete it.
     *
     * @param result the Pigeon result of the call that starts the flow
     * @return true if the call now waits for the flow; false if it was rejected
     */
    boolean start(BiometricsPigeon.Result<String> result) {
        if (this.result != null) {
            result.error(new BiometricsPigeon.FlutterError(SBIError.SBI_CAPTURE_IN_PROGRESS.getErrorCode(),
                    SBIError.SBI_CAPTURE_IN_PROGRESS.getErrorMessage(), null));
            return false;
        }
        this.result = result;
        return true;
    }

    /** @param value the success value sent to Dart */
    void complete(String value) {
        BiometricsPigeon.Result<String> pending = take();
        if (pending != null) {
            pending.success(value);
        }
    }

    /**
     * @param code    error code Dart receives as {@code PlatformException.code}, e.g. {@code REG-SBI-118}
     * @param message message Dart receives as {@code PlatformException.message}
     */
    void fail(String code, String message) {
        BiometricsPigeon.Result<String> pending = take();
        if (pending != null) {
            pending.error(new BiometricsPigeon.FlutterError(code, message, null));
        }
    }

    /** Hands out the waiting result once, so Dart never gets a second reply ("Reply already submitted"). */
    private BiometricsPigeon.Result<String> take() {
        BiometricsPigeon.Result<String> pending = result;
        result = null;
        return pending;
    }
}
