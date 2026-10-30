package io.mosip.registration.clientmanager.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;

import io.mosip.registration.packetmanager.cbeffutil.jaxbclasses.BIR;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.evidence.PacketEvidence;
import io.mosip.registration.livenessmanager.session.LivenessSessionStore;

/**
 * Adds the API.md §9 liveness keys (LIVENESS_DECISION, _SCORE, _THRESHOLD, _MODE, _CHALLENGES, _ENGINE_VERSION and,
 * from the real engine, _EVIDENCE_DIGEST) to a resident's face BIR, from the passed check that let the face capture
 * through (B-12), not from whatever check ran last. Without one, for example when liveness is off, the BIR stays as
 * MOSIP builds it.
 */
public final class LivenessPacketEvidence {

    private static final String RESIDENT_WORKFLOW = "RESIDENT_REGISTRATION";
    private static final String FACE_MODALITY = "Face";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LivenessPacketEvidence() {
    }

    /**
     * @param bir     the BIR built for {@code dto}
     * @param dto     the captured biometric
     * @param fieldId the biometric field, e.g. {@code individualBiometrics}
     */
    public static void addTo(BIR bir, BiometricsDto dto, String fieldId) {
        addTo(LivenessSessionStore.shared(), bir, dto, fieldId);
    }

    static void addTo(LivenessSessionStore store, BIR bir, BiometricsDto dto, String fieldId) {
        if (dto == null || !FACE_MODALITY.equalsIgnoreCase(dto.getModality()) || dto.isException()) {
            return;
        }
        // Each face attempt carries the check that let it through, found by the nonce the device signed back
        String nonce = signedTransactionId(dto);
        LivenessResult result = store.capturedResult(RESIDENT_WORKFLOW, fieldId, nonce);
        LivenessPolicy policy = store.capturedPolicy(RESIDENT_WORKFLOW, fieldId, nonce);
        if (result == null || policy == null || result.finalState() != SessionState.PASSED) {
            return;
        }
        if (bir.getOthers() == null) {
            bir.setOthers(new HashMap<>());
        }
        bir.getOthers().putAll(PacketEvidence.of(result, policy));
    }

    /** @return the transactionId in the capture's signed SBI data, or null when there is none */
    private static String signedTransactionId(BiometricsDto dto) {
        if (dto.getDecodedBioResponse() == null) {
            return null;
        }
        try {
            JsonNode id = MAPPER.readTree(dto.getDecodedBioResponse()).get("transactionId");
            return id == null || id.isNull() ? null : id.asText();
        } catch (IOException e) {
            return null;
        }
    }
}
