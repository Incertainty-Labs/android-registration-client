package io.mosip.registration_client.api_services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;

/**
 * The face captured for operator or supervisor face authentication (field {@code operatorAuth}, B-09). It is held
 * here, never in the resident's registration or the operator's onboarding, and used for one match only.
 */
@Singleton
public final class OperatorAuthCapture {

    /** The field id the Flutter side captures the authenticating face under. */
    public static final String FIELD_ID = "operatorAuth";

    private String transactionId;
    private List<BiometricsDto> faces = Collections.emptyList();

    @Inject
    public OperatorAuthCapture() {
    }

    /** @param captureTransactionId the RCAPTURE request's transaction id; drops any earlier capture */
    synchronized void begin(String captureTransactionId) {
        transactionId = captureTransactionId;
        faces = Collections.emptyList();
    }

    /** @return the transaction id of the capture in progress, or null */
    synchronized String transactionId() {
        return transactionId;
    }

    /** @param captured the faces the SBI returned */
    synchronized void hold(List<BiometricsDto> captured) {
        faces = new ArrayList<>(captured);
    }

    /** @return the held faces, which are cleared, so a capture is matched once; empty when none */
    public synchronized List<BiometricsDto> take() {
        List<BiometricsDto> taken = faces;
        faces = Collections.emptyList();
        transactionId = null;
        return taken;
    }
}
