package io.mosip.registration.clientmanager.util;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.packetmanager.cbeffutil.jaxbclasses.BIR;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.liveness.api.ChallengeOutcome;
import io.mosip.registration.liveness.api.ChallengeProgress;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.livenessmanager.session.LivenessSessionStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** B-12: the face BIR carries the API.md §9 liveness keys of the field's passed check (TC-AND-18). */
public class LivenessPacketEvidenceTest {

    private static final String WORKFLOW = "RESIDENT_REGISTRATION";
    private static final String FACE = "individualBiometrics";
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    private final LivenessSessionStore store = new LivenessSessionStore();
    private BIR bir;

    @Before
    public void setUp() {
        bir = new BIR.BIRBuilder().build();
        bir.setOthers(new HashMap<>());
        bir.getOthers().put("RETRIES", "1");
    }

    @Test
    public void tcAnd18_passedFace_carriesTheLivenessKeys() {
        runCheck(passed());

        LivenessPacketEvidence.addTo(store, bir, face(false), FACE);

        Map<String, String> others = bir.getOthers();
        assertEquals("LIVE", others.get("LIVENESS_DECISION"));
        assertEquals("0.93", others.get("LIVENESS_SCORE"));
        assertEquals("0.80", others.get("LIVENESS_THRESHOLD"));
        assertEquals("HYBRID", others.get("LIVENESS_MODE"));
        assertEquals("SMILE:PASSED,NEUTRAL_HOLD:PASSED", others.get("LIVENESS_CHALLENGES"));
        assertEquals("1", others.get("RETRIES"));
    }

    @Test
    public void tcAnd18_noCheck_leavesTheBirAsBefore() {
        LivenessPacketEvidence.addTo(store, bir, face(false), FACE);

        assertEquals(1, bir.getOthers().size());
    }

    @Test
    public void tcAnd18_failedCheck_addsNothing() {
        runCheck(LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_FAILED).build());

        LivenessPacketEvidence.addTo(store, bir, face(false), FACE);

        assertFalse(bir.getOthers().containsKey("LIVENESS_DECISION"));
    }

    @Test
    public void tcAnd18_otherModalitiesAndTheExceptionPhoto_addNothing() {
        runCheck(passed());
        BiometricsDto iris = face(false);
        iris.setModality("Iris");

        LivenessPacketEvidence.addTo(store, bir, iris, FACE);
        LivenessPacketEvidence.addTo(store, bir, face(true), FACE);

        assertFalse(bir.getOthers().containsKey("LIVENESS_DECISION"));
    }

    @Test
    public void tcAnd18_aLaterFailedCheck_keepsTheCapturedFacesEvidence() {
        runCheck(passed());
        laterCheck(LivenessResult.builder(SessionState.FAILED, Decision.INCONCLUSIVE)
                .errorCode(LivenessErrorCode.CHALLENGE_FAILED).build());

        LivenessPacketEvidence.addTo(store, bir, face(false), FACE);

        assertEquals("LIVE", bir.getOthers().get("LIVENESS_DECISION"));
        assertEquals("0.93", bir.getOthers().get("LIVENESS_SCORE"));
    }

    @Test
    public void tcAnd18_anUnusedLaterPass_doesNotReplaceTheEvidence() {
        runCheck(passed());
        laterCheck(LivenessResult.builder(SessionState.PASSED, Decision.LIVE).passiveScore(0.50).build());

        LivenessPacketEvidence.addTo(store, bir, face(false), FACE);

        assertEquals("0.93", bir.getOthers().get("LIVENESS_SCORE"));
    }

    @Test
    public void tcAnd18_aPassNeverUsedForACapture_addsNothing() {
        laterCheck(passed());

        LivenessPacketEvidence.addTo(store, bir, face(false), FACE);

        assertFalse(bir.getOthers().containsKey("LIVENESS_DECISION"));
    }

    @Test
    public void tcAnd18_anotherFieldsPass_addsNothing() {
        runCheck(passed());

        LivenessPacketEvidence.addTo(store, bir, face(false), "introducerBiometrics");

        assertFalse(bir.getOthers().containsKey("LIVENESS_DECISION"));
    }

    @Test
    public void tcAnd18_eachFaceCapture_carriesTheEvidenceOfTheCheckThatAllowedIt() {
        runCheck(passed("nonce-1", 0.93));
        runCheck(passed("nonce-2", 0.81));
        BIR second = new BIR.BIRBuilder().build();

        LivenessPacketEvidence.addTo(store, bir, signedFace("nonce-1"), FACE);
        LivenessPacketEvidence.addTo(store, second, signedFace("nonce-2"), FACE);

        assertEquals("0.93", bir.getOthers().get("LIVENESS_SCORE"));
        assertEquals("0.81", second.getOthers().get("LIVENESS_SCORE"));
    }

    @Test
    public void tcAnd18_aCaptureNoCheckSignedFor_addsNothing() {
        runCheck(passed("nonce-1", 0.93));

        LivenessPacketEvidence.addTo(store, bir, signedFace("someone-elses-nonce"), FACE);

        assertFalse(bir.getOthers().containsKey("LIVENESS_DECISION"));
    }

    private void runCheck(LivenessResult result) {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigLoader.PREFIX + "enabled", "true");
        LivenessPolicy policy = LivenessConfigLoader.load(config, Workflow.RESIDENT_REGISTRATION);
        store.begin(WORKFLOW, FACE, policy, 0);
        store.complete(WORKFLOW, FACE, result, false);
        // The capture that follows uses the pass (#23 guard), and the device's answer is accepted, which marks this
        // check as the captured one
        store.consumePass(WORKFLOW, FACE, 120_000L);
        store.acceptCapture(WORKFLOW, FACE);
    }

    /** A later check on the same field, with no capture after it. */
    private void laterCheck(LivenessResult result) {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigLoader.PREFIX + "enabled", "true");
        store.begin(WORKFLOW, FACE, LivenessConfigLoader.load(config, Workflow.RESIDENT_REGISTRATION), 0);
        store.complete(WORKFLOW, FACE, result, false);
    }

    private static LivenessResult passed() {
        return LivenessResult.builder(SessionState.PASSED, Decision.LIVE).passiveScore(0.93).nonce(NONCE)
                .challenges(Arrays.asList(
                        new ChallengeOutcome(ChallengeType.SMILE, ChallengeProgress.PASSED, 900L),
                        new ChallengeOutcome(ChallengeType.NEUTRAL_HOLD, ChallengeProgress.PASSED, 1000L)))
                .build();
    }

    private static LivenessResult passed(String nonce, double score) {
        return LivenessResult.builder(SessionState.PASSED, Decision.LIVE).passiveScore(score).nonce(nonce).build();
    }

    /** A face capture whose signed SBI data echoes {@code transactionId}, as the device signs it. */
    private static BiometricsDto signedFace(String transactionId) {
        String decoded = "{\"bioType\":\"Face\",\"transactionId\":\"" + transactionId + "\",\"bioValue\":\"\"}";
        return new BiometricsDto("Face", null, null, null, false, decoded, null, false, 0, 0.0, 0.0f);
    }

    /** A face capture signed for the nonce of {@link #passed()}. */
    private static BiometricsDto face(boolean exception) {
        BiometricsDto signed = signedFace(NONCE);
        return new BiometricsDto("Face", null, null, null, exception, signed.getDecodedBioResponse(), null, false, 0,
                0.0, 0.0f);
    }
}
