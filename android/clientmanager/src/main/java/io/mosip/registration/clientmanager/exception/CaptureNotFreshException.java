package io.mosip.registration.clientmanager.exception;

/**
 * The signed capture was not made for this session or not recently (LIV-014, B-07): the device did not echo the
 * nonce, or signed it too far from the reference time. The message is generic; {@link #reason()} is for the audit
 * only.
 */
public class CaptureNotFreshException extends BiometricsServiceException {

    /** The client error code for LIV-014 (API.md §7). */
    public static final String CODE = "REG-LIV-014";

    /** What the operator sees: the same generic text as any other liveness failure. */
    public static final String MESSAGE = "Face verification could not be completed. Please try again.";

    private final String reason;

    /** @param reason why the capture is not fresh, for the audit only */
    public CaptureNotFreshException(String reason) {
        super(CODE, MESSAGE);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
