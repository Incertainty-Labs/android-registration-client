package io.mosip.registration.livenessmanager;

import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.liveness.config.LivenessConfigLoader;

/**
 * The liveness config a session runs with: the synced liveness keys plus the client's face retry limit, with the
 * build's overrides on top. Overrides let a demo or test build turn liveness on without changing the server
 * config; a build without them behaves as the server says.
 */
public final class LivenessSettings {

    /** The client's own face attempt limit (API.md §5). */
    public static final String FACE_RETRIES_KEY = "mosip.registration.num_of_face_retries";

    private LivenessSettings() {
    }

    /**
     * @param synced    synced global params; only the liveness keys and {@link #FACE_RETRIES_KEY} are kept
     * @param overrides {@code key=value} pairs separated by {@code ;}, for example
     *                  {@code enabled=true;stub.force=PASS_ACTIVE}; a key not starting with {@code mosip.} is
     *                  under {@link LivenessConfigLoader#PREFIX}. Null or blank for none; malformed pairs are
     *                  ignored
     * @return the merged config
     */
    public static Map<String, String> merge(Map<String, ?> synced, String overrides) {
        Map<String, String> config = new HashMap<>();
        for (Map.Entry<String, ?> entry : synced.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() != null && key != null
                    && (key.startsWith(LivenessConfigLoader.PREFIX) || key.equals(FACE_RETRIES_KEY))) {
                config.put(key, entry.getValue().toString().trim());
            }
        }
        if (overrides != null) {
            for (String pair : overrides.split(";")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? "" : pair.substring(0, eq).trim();
                if (!key.isEmpty()) {
                    config.put(key.startsWith("mosip.") ? key : LivenessConfigLoader.PREFIX + key,
                            pair.substring(eq + 1).trim());
                }
            }
        }
        return config;
    }
}
