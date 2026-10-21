package io.mosip.registration.clientmanager.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which SBI device certification levels (L0, L1) the client accepts, from the
 * {@code mosip.registration.face.liveness.device.accepted.certifications} config value.
 */
public final class DeviceCertificationPolicy {

    /** Config key listing the accepted levels, comma-separated. */
    public static final String CONFIG_KEY = "mosip.registration.face.liveness.device.accepted.certifications";

    /** Accepted levels when nothing usable is configured. */
    public static final String DEFAULT_ACCEPTED_CERTIFICATIONS = "L0,L1";

    /** The levels SBI 0.9.5 devices report. */
    private static final List<String> KNOWN_LEVELS = Collections.unmodifiableList(Arrays.asList("L0", "L1"));

    private final Set<String> accepted;
    private final List<String> warnings;

    private DeviceCertificationPolicy(Set<String> accepted, List<String> warnings) {
        this.accepted = accepted;
        this.warnings = warnings;
    }

    /**
     * Builds the policy from the configured comma-separated list. Spaces and case are ignored.
     *
     * @param configValue configured levels, for example {@code "L0,L1"}; null, or a value with no
     *                    non-blank entry, uses {@link #DEFAULT_ACCEPTED_CERTIFICATIONS}
     * @return the policy
     */
    public static DeviceCertificationPolicy fromConfig(String configValue) {
        Set<String> levels = parse(configValue);
        if (levels.isEmpty()) {
            levels = parse(DEFAULT_ACCEPTED_CERTIFICATIONS);
        }
        List<String> warnings = new ArrayList<>();
        for (String level : levels) {
            if (!KNOWN_LEVELS.contains(level)) {
                warnings.add("Unknown certification level '" + level + "' in " + CONFIG_KEY
                        + "; SBI devices report only L0 or L1, so no device can match it");
            }
        }
        return new DeviceCertificationPolicy(Collections.unmodifiableSet(levels), Collections.unmodifiableList(warnings));
    }

    private static Set<String> parse(String value) {
        Set<String> levels = new LinkedHashSet<>();
        if (value == null) {
            return levels;
        }
        for (String level : value.split(",")) {
            String normalised = normalise(level);
            if (!normalised.isEmpty()) {
                levels.add(normalised);
            }
        }
        return levels;
    }

    /**
     * @param certification the {@code certification} the device reports in its device info
     * @return true if the client accepts devices of that level
     */
    public boolean accepts(String certification) {
        return certification != null && accepted.contains(normalise(certification));
    }

    /** @return the accepted levels, comma-separated in config order, e.g. {@code L0,L1} (for messages and audit) */
    public String acceptedLevels() {
        return String.join(",", accepted);
    }

    /** @return one message per configured level other than L0 and L1, in config order; unmodifiable */
    public List<String> warnings() {
        return warnings;
    }

    private static String normalise(String level) {
        return level.trim().toUpperCase(Locale.ROOT);
    }
}
