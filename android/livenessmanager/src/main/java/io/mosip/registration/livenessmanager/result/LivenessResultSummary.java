package io.mosip.registration.livenessmanager.result;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;

/**
 * What the Flutter UI may know about one liveness attempt: the fields of the Pigeon
 * {@code LivenessResultDto} (API.md §10). It never carries diagnostics, so PAD details cannot
 * reach the UI. Immutable.
 */
public final class LivenessResultSummary {

    /** Message key shown when liveness passes. */
    static final String SUCCESS_MESSAGE_KEY = "liveness.feedback.success";

    private final String decision;
    private final String finalState;
    private final String errorCode;
    private final String messageKey;
    private final Double passiveScore;
    private final List<String> challenges;
    private final int attempt;
    private final int maxAttempts;
    private final boolean retryAllowed;

    private LivenessResultSummary(LivenessResult result, int attempt, int maxAttempts, boolean retryAllowed,
                                  boolean diagnosticsEnabled) {
        this.decision = result.decision().name();
        this.finalState = result.finalState().name();
        this.errorCode = result.errorCode() == null ? null : result.errorCode().code();
        this.messageKey = messageKeyFor(result, retryAllowed);
        this.passiveScore = diagnosticsEnabled ? Double.valueOf(result.passiveScore()) : null;
        List<String> list = new ArrayList<>();
        for (ChallengeOutcome c : result.challenges()) {
            list.add(c.type().name() + ":" + c.outcome().name());
        }
        this.challenges = Collections.unmodifiableList(list);
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
        this.retryAllowed = retryAllowed;
    }

    private LivenessResultSummary(JSONObject json) throws JSONException {
        this.decision = json.getString("decision");
        this.finalState = json.getString("finalState");
        this.errorCode = json.optString("errorCode", null);
        this.messageKey = json.optString("messageKey", null);
        this.passiveScore = json.has("passiveScore") ? Double.valueOf(json.getDouble("passiveScore")) : null;
        List<String> list = new ArrayList<>();
        JSONArray array = json.getJSONArray("challenges");
        for (int i = 0; i < array.length(); i++) {
            list.add(array.getString(i));
        }
        this.challenges = Collections.unmodifiableList(list);
        this.attempt = json.getInt("attempt");
        this.maxAttempts = json.getInt("maxAttempts");
        this.retryAllowed = json.getBoolean("retryAllowed");
    }

    /**
     * @param result             the engine result
     * @param attempt            the attempt this result belongs to, 1-based
     * @param maxAttempts        the attempt limit for the workflow
     * @param retryAllowed       whether another attempt is allowed after this one
     * @param diagnosticsEnabled whether {@code diagnostics.enabled} is on
     * @return the summary for the UI
     */
    public static LivenessResultSummary of(LivenessResult result, int attempt, int maxAttempts,
                                           boolean retryAllowed, boolean diagnosticsEnabled) {
        return new LivenessResultSummary(result, attempt, maxAttempts, retryAllowed, diagnosticsEnabled);
    }

    /**
     * Reads a summary written by {@link #toJson()}.
     *
     * @param json the JSON text
     * @return the summary
     * @throws JSONException if the text is not a summary
     */
    public static LivenessResultSummary fromJson(String json) throws JSONException {
        return new LivenessResultSummary(new JSONObject(json));
    }

    /**
     * @return the summary as JSON, for the {@code liveness.result} extra (API.md §10); null fields are left
     *         out
     */
    public String toJson() {
        try {
            JSONObject json = new JSONObject()
                    .put("decision", decision)
                    .put("finalState", finalState)
                    .put("challenges", new JSONArray(challenges))
                    .put("attempt", attempt)
                    .put("maxAttempts", maxAttempts)
                    .put("retryAllowed", retryAllowed);
            // JSONObject.put leaves out a null value
            json.put("errorCode", errorCode).put("messageKey", messageKey).put("passiveScore", passiveScore);
            return json.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot write the liveness summary", e);
        }
    }

    private static String messageKeyFor(LivenessResult result, boolean retryAllowed) {
        if (result.finalState() == SessionState.PASSED) {
            return SUCCESS_MESSAGE_KEY;
        }
        if (result.finalState() == SessionState.FAILED && !retryAllowed) {
            return LivenessErrorCode.MAX_RETRIES_EXCEEDED.messageKey();
        }
        return result.errorCode() == null ? null : result.errorCode().messageKey();
    }

    /** @return LIVE, ATTACK_SUSPECTED or INCONCLUSIVE */
    public String decision() {
        return decision;
    }

    /** @return PASSED, FAILED, ERROR or CANCELLED */
    public String finalState() {
        return finalState;
    }

    /** @return the LIV-0xx code, or null */
    public String errorCode() {
        return errorCode;
    }

    /** @return the message key the UI shows, or null when nothing is shown */
    public String messageKey() {
        return messageKey;
    }

    /** @return the fused passive score, or null unless diagnostics are enabled; never shown in the normal UI */
    public Double passiveScore() {
        return passiveScore;
    }

    /** @return challenges as {@code TYPE:OUTCOME}, in order; unmodifiable */
    public List<String> challenges() {
        return challenges;
    }

    /** @return the attempt this result belongs to, 1-based */
    public int attempt() {
        return attempt;
    }

    /** @return the attempt limit for the workflow */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** @return whether another attempt is allowed */
    public boolean retryAllowed() {
        return retryAllowed;
    }
}
