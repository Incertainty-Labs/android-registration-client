package io.mosip.registration.livenessmanager;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.livenessmanager.result.LivenessResultSummary;

/**
 * How a caller starts {@link LivenessActivity} and reads what it returns (API.md §10). The activity always
 * finishes with {@code RESULT_OK} and a {@code liveness.result} extra, a cancel included; a caller that gets no
 * extra (the activity died) treats it as cancelled.
 */
public final class LivenessContract {

    /** Request code for {@code startActivityForResult}; 1 to 5 are the SBI captures. */
    public static final int REQUEST_CODE = 6;

    static final String EXTRA_WORKFLOW = "liveness.workflow";
    static final String EXTRA_FIELD_ID = "liveness.fieldId";
    static final String EXTRA_ATTEMPT = "liveness.attempt";
    static final String EXTRA_CONFIG = "liveness.config";
    static final String EXTRA_RESULT = "liveness.result";

    private LivenessContract() {
    }

    /** What {@link LivenessActivity} runs. Immutable. */
    public static final class Request {
        private final String workflow;
        private final String fieldId;
        private final int attempt;
        private final Map<String, String> config;

        /**
         * @param workflow the {@code Workflow} name
         * @param fieldId  the biometric field id, or {@code operatorAuth}
         * @param attempt  the attempt about to run, for logs; the attempt count itself is in
         *                 {@code LivenessSessionStore}
         * @param config   the {@code mosip.registration.face.liveness.*} keys and
         *                 {@code mosip.registration.num_of_face_retries}; copied
         */
        public Request(String workflow, String fieldId, int attempt, Map<String, String> config) {
            this.workflow = workflow;
            this.fieldId = fieldId;
            this.attempt = attempt;
            this.config = Collections.unmodifiableMap(new HashMap<>(config));
        }

        /** @return the {@code Workflow} name */
        public String workflow() {
            return workflow;
        }

        /** @return the field id */
        public String fieldId() {
            return fieldId;
        }

        /** @return the attempt about to run */
        public int attempt() {
            return attempt;
        }

        /** @return the liveness config; unmodifiable */
        public Map<String, String> config() {
            return config;
        }
    }

    /**
     * @param context the caller
     * @param request what to run
     * @return the intent that opens {@link LivenessActivity}
     */
    public static Intent createIntent(Context context, Request request) {
        Bundle config = new Bundle();
        for (Map.Entry<String, String> entry : request.config().entrySet()) {
            config.putString(entry.getKey(), entry.getValue());
        }
        return new Intent(context, LivenessActivity.class)
                .putExtra(EXTRA_WORKFLOW, request.workflow())
                .putExtra(EXTRA_FIELD_ID, request.fieldId())
                .putExtra(EXTRA_ATTEMPT, request.attempt())
                .putExtra(EXTRA_CONFIG, config);
    }

    /**
     * @param intent the intent {@link LivenessActivity} was started with
     * @return the request
     * @throws IllegalArgumentException if the workflow or the field id is missing
     */
    public static Request parseRequest(Intent intent) {
        String workflow = intent.getStringExtra(EXTRA_WORKFLOW);
        String fieldId = intent.getStringExtra(EXTRA_FIELD_ID);
        if (workflow == null || fieldId == null) {
            throw new IllegalArgumentException("Liveness request needs " + EXTRA_WORKFLOW + " and " + EXTRA_FIELD_ID);
        }
        Map<String, String> config = new HashMap<>();
        Bundle bundle = intent.getBundleExtra(EXTRA_CONFIG);
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                config.put(key, bundle.getString(key));
            }
        }
        return new Request(workflow, fieldId, intent.getIntExtra(EXTRA_ATTEMPT, 1), config);
    }

    /**
     * @param summary what the UI may show
     * @return the data intent {@link LivenessActivity} finishes with
     */
    public static Intent resultIntent(LivenessResultSummary summary) {
        return new Intent().putExtra(EXTRA_RESULT, summary.toJson());
    }

    /**
     * @param data the data intent from {@code onActivityResult}, or null
     * @return the summary, or null when there is none (treat as cancelled)
     */
    public static LivenessResultSummary parseResult(Intent data) {
        String json = data == null ? null : data.getStringExtra(EXTRA_RESULT);
        if (json == null) {
            return null;
        }
        try {
            return LivenessResultSummary.fromJson(json);
        } catch (JSONException e) {
            return null;
        }
    }
}
