package io.mosip.registration.clientmanager.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which SBI device certification levels (L0, L1) the client accepts, from the
 * {@code mosip.registration.face.liveness.device.accepted.certifications} config value.
 */
public final class DeviceCertificationPolicy {

    /** Accepted levels when nothing usable is configured. */
    public static final String DEFAULT_ACCEPTED_CERTIFICATIONS = "L0,L1";

    private final Set<String> accepted;

    private DeviceCertificationPolicy(Set<String> accepted) {
        this.accepted = accepted;
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
        return new DeviceCertificationPolicy(Collections.unmodifiableSet(levels));
    }

    private static Set<String> parse(String value) {
        Set<String> levels = new HashSet<>();
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

    private static String normalise(String level) {
        return level.trim().toUpperCase(Locale.ROOT);
    }
}
