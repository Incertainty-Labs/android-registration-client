package io.mosip.registration_client.api_services;

import io.mosip.registration_client.model.BiometricsPigeon;

/**
 * The Dart call waiting for an SBI capture flow (discover, info, capture). Every way the flow can end must complete
 * it, or the Dart side waits forever.
 */
final class PendingSbiCall {

    private BiometricsPigeon.Result<String> result;

    /** @param result the Pigeon result of the call that starts the flow */
    void start(BiometricsPigeon.Result<String> result) {
        this.result = result;
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
