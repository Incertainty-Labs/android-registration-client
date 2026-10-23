package io.mosip.registration.livenessmanager;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LivenessSettingsTest {

    private static final String PREFIX = "mosip.registration.face.liveness.";

    @Test
    public void tcAnd_synced_keepsTheLivenessKeysAndTheFaceRetryLimit() {
        Map<String, Object> synced = new HashMap<>();
        synced.put(PREFIX + "max.retries", 3);
        synced.put("mosip.registration.num_of_face_retries", "2");
        synced.put("mosip.registration.gps_device_enable_flag", "N");

        Map<String, String> config = LivenessSettings.merge(synced, null);

        assertEquals("3", config.get(PREFIX + "max.retries"));
        assertEquals("2", config.get("mosip.registration.num_of_face_retries"));
        assertFalse(config.containsKey("mosip.registration.gps_device_enable_flag"));
    }

    @Test
    public void tcAnd_overrides_winOverSyncedValuesAndTakeShortKeys() {
        Map<String, Object> synced = new HashMap<>();
        synced.put(PREFIX + "enabled", "false");

        Map<String, String> config = LivenessSettings.merge(synced, "enabled=true; stub.force = PASS_ACTIVE");

        assertEquals("true", config.get(PREFIX + "enabled"));
        assertEquals("PASS_ACTIVE", config.get(PREFIX + "stub.force"));
    }

    @Test
    public void tcAnd_overrides_acceptFullKeys() {
        Map<String, String> config = LivenessSettings.merge(new HashMap<>(),
                "mosip.registration.num_of_face_retries=1");

        assertEquals("1", config.get("mosip.registration.num_of_face_retries"));
    }

    @Test
    public void tcAnd_malformedOverrides_areIgnored() {
        Map<String, String> config = LivenessSettings.merge(new HashMap<>(), "enabled;=x; ;max.retries=2");

        assertEquals(1, config.size());
        assertEquals("2", config.get(PREFIX + "max.retries"));
    }

    @Test
    public void tcAnd_nullSyncedValue_isLeftOut() {
        Map<String, Object> synced = new HashMap<>();
        synced.put(PREFIX + "mode", null);

        assertFalse(LivenessSettings.merge(synced, "").containsKey(PREFIX + "mode"));
    }
}
