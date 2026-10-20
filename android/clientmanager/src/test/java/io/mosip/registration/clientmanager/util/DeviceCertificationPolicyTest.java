package io.mosip.registration.clientmanager.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeviceCertificationPolicyTest {

    @Test
    public void tcAnd03_withNoConfiguredValue_acceptsL0AndL1AndRejectsL2() {
        DeviceCertificationPolicy policy = DeviceCertificationPolicy.fromConfig(null);

        assertTrue(policy.accepts("L0"));
        assertTrue(policy.accepts("L1"));
        assertFalse(policy.accepts("L2"));
    }

    @Test
    public void tcAnd03_withOnlyL1Configured_rejectsL0() {
        DeviceCertificationPolicy policy = DeviceCertificationPolicy.fromConfig("L1");

        assertTrue(policy.accepts("L1"));
        assertFalse(policy.accepts("L0"));
    }

    @Test
    public void tcAnd03_withSpacesAndLowerCase_matchesLevelsIgnoringBoth() {
        DeviceCertificationPolicy policy = DeviceCertificationPolicy.fromConfig(" l0 , L1 ");

        assertTrue(policy.accepts("L0"));
        assertTrue(policy.accepts("l1"));
    }

    @Test
    public void tcAnd03_withBlankOrEmptyEntries_fallsBackToL0AndL1() {
        for (String blank : new String[] {"", "  ", " , "}) {
            DeviceCertificationPolicy policy = DeviceCertificationPolicy.fromConfig(blank);

            assertTrue("L0 for '" + blank + "'", policy.accepts("L0"));
            assertTrue("L1 for '" + blank + "'", policy.accepts("L1"));
        }
    }
}
