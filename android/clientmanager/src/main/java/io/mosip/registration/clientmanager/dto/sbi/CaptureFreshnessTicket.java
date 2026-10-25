package io.mosip.registration.clientmanager.dto.sbi;

/**
 * What one RCAPTURE must prove to be fresh (B-07, API.md §3.7 CaptureFreshness): the nonce sent as its
 * {@code transactionId}, which the device must sign back, and the time its signed timestamp must be close to. With
 * liveness on, that is the session nonce and when the check passed; with liveness off, a new nonce and when the
 * request was sent. Immutable.
 */
public final class CaptureFreshnessTicket {

    private final String nonce;
    private final long referenceEpochMs;
    private final long maxAgeMs;
    private final String workflow;
    private final String fieldId;

    /**
     * @param nonce            the RCAPTURE {@code transactionId}
     * @param referenceEpochMs when liveness passed, or when the request was sent if liveness is off
     * @param maxAgeMs         the allowed distance of the signed timestamp from the reference
     *                         ({@code bind.freshness.max.age.ms})
     * @param workflow         the liveness workflow whose pass the capture uses, or null when liveness is off
     * @param fieldId          the biometric field captured
     */
    public CaptureFreshnessTicket(String nonce, long referenceEpochMs, long maxAgeMs, String workflow,
                                  String fieldId) {
        this.nonce = nonce;
        this.referenceEpochMs = referenceEpochMs;
        this.maxAgeMs = maxAgeMs;
        this.workflow = workflow;
        this.fieldId = fieldId;
    }

    public String nonce() {
        return nonce;
    }

    public long referenceEpochMs() {
        return referenceEpochMs;
    }

    public long maxAgeMs() {
        return maxAgeMs;
    }

    /** @return the liveness workflow whose pass the capture uses, or null when liveness is off */
    public String workflow() {
        return workflow;
    }

    public String fieldId() {
        return fieldId;
    }
}
