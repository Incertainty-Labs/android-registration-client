package io.mosip.registration.livenessmanager.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import io.mosip.registration.liveness.api.Challenge;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.PromptLevel;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.UserPrompt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LivenessScreenModelTest {

    @Test
    public void tcUx_s2_waitingForFace_showsPositionStatusAndOnlyCancel() {
        LivenessScreenModel model = new LivenessScreenModel();

        ScreenState screen = model.onStateChanged(SessionState.WAITING_FOR_FACE,
                new UserPrompt("liveness.status.position", PromptLevel.INFO));

        assertEquals("liveness.status.position", screen.statusKey());
        assertEquals(ScreenState.Tone.INFO, screen.tone());
        assertNull(screen.promptKey());
        assertEquals(Collections.singletonList(ScreenState.Button.CANCEL), screen.buttons());
    }

    @Test
    public void tcUx_s3_guidanceWhileWaiting_keepsStatusAndShowsGuidance() {
        LivenessScreenModel model = new LivenessScreenModel();
        model.onStateChanged(SessionState.WAITING_FOR_FACE, new UserPrompt("liveness.status.position", PromptLevel.INFO));

        ScreenState screen = model.onStateChanged(SessionState.WAITING_FOR_FACE,
                new UserPrompt("liveness.guidance.improve_lighting", PromptLevel.GUIDANCE));

        assertEquals("liveness.status.position", screen.statusKey());
        assertEquals("liveness.guidance.improve_lighting", screen.guidanceKey());
        assertEquals(ScreenState.Tone.GUIDANCE, screen.tone());
    }

    @Test
    public void tcUx_s5_challenge_showsStepPromptAndDirectionCue() {
        LivenessScreenModel model = new LivenessScreenModel();

        ScreenState screen = model.onChallenge(new Challenge(ChallengeType.TURN_LEFT, 5000L), 2, 3);

        assertEquals("liveness.progress.step", screen.statusKey());
        assertEquals(2, screen.stepIndex());
        assertEquals(3, screen.stepTotal());
        assertEquals("liveness.prompt.turn_left", screen.promptKey());
        assertEquals(ScreenState.Cue.LEFT, screen.cue());
        assertEquals(ScreenState.Tone.INSTRUCTION, screen.tone());
    }

    @Test
    public void tcUx_s5_everyChallengeType_hasItsPromptAndCue() {
        Object[][] expected = {
                {ChallengeType.TURN_LEFT, "liveness.prompt.turn_left", ScreenState.Cue.LEFT},
                {ChallengeType.TURN_RIGHT, "liveness.prompt.turn_right", ScreenState.Cue.RIGHT},
                {ChallengeType.LOOK_UP, "liveness.prompt.look_up", ScreenState.Cue.UP},
                {ChallengeType.LOOK_DOWN, "liveness.prompt.look_down", ScreenState.Cue.DOWN},
                {ChallengeType.SMILE, "liveness.prompt.smile", ScreenState.Cue.SMILE},
                {ChallengeType.OPEN_MOUTH, "liveness.prompt.open_mouth", ScreenState.Cue.MOUTH},
                {ChallengeType.CLOSE_EYES, "liveness.prompt.close_eyes", ScreenState.Cue.EYES},
                {ChallengeType.BLINK, "liveness.prompt.blink", ScreenState.Cue.EYES},
                {ChallengeType.NEUTRAL_HOLD, "liveness.prompt.neutral", ScreenState.Cue.NONE},
        };
        for (Object[] row : expected) {
            ScreenState screen = new LivenessScreenModel().onChallenge(new Challenge((ChallengeType) row[0], 5000L), 1, 2);

            assertEquals(row[0] + " prompt", row[1], screen.promptKey());
            assertEquals(row[0] + " cue", row[2], screen.cue());
        }
    }

    @Test
    public void tcUx_s4_passiveCheck_showsCheckingAndNoActionNeeded() {
        ScreenState screen = new LivenessScreenModel().onStateChanged(SessionState.PASSIVE_CHECK,
                new UserPrompt("liveness.status.checking", PromptLevel.INFO));

        assertEquals("liveness.status.checking", screen.statusKey());
        assertEquals("liveness.status.no_action", screen.promptKey());
        assertEquals(ScreenState.Cue.NONE, screen.cue());
        assertEquals(0, screen.stepTotal());
    }

    @Test
    public void tcUx_s5_progress_fillsTheRingAndKeepsTheChallenge() {
        LivenessScreenModel model = new LivenessScreenModel();
        model.onChallenge(new Challenge(ChallengeType.SMILE, 5000L), 1, 2);

        ScreenState screen = model.onProgress(0.5f);

        assertEquals(0.5f, screen.ringProgress(), 0.0001f);
        assertEquals("liveness.prompt.smile", screen.promptKey());
        assertEquals(ScreenState.Cue.SMILE, screen.cue());
        assertEquals(1, screen.stepIndex());
        assertEquals(2, screen.stepTotal());
    }

    @Test
    public void tcUx_s6_feedbackDuringChallenge_showsToastAndKeepsTheChallenge() {
        LivenessScreenModel model = new LivenessScreenModel();
        model.onChallenge(new Challenge(ChallengeType.TURN_RIGHT, 5000L), 2, 3);

        ScreenState screen = model.onStateChanged(SessionState.ACTIVE_CHALLENGE,
                new UserPrompt("liveness.feedback.detected", PromptLevel.SUCCESS));

        assertEquals("liveness.feedback.detected", screen.toastKey());
        assertEquals("liveness.progress.step", screen.statusKey());
        assertEquals("liveness.prompt.turn_right", screen.promptKey());
        assertEquals(2, screen.stepIndex());
    }

    @Test
    public void tcUx_s7_neutralHold_showsHoldPromptKeepsStatusAndStep() {
        LivenessScreenModel model = new LivenessScreenModel();
        model.onChallenge(new Challenge(ChallengeType.SMILE, 5000L), 3, 3);

        ScreenState screen = model.onStateChanged(SessionState.NEUTRAL_HOLD,
                new UserPrompt("liveness.prompt.neutral", PromptLevel.INFO));

        assertEquals("liveness.prompt.neutral", screen.promptKey());
        assertEquals(ScreenState.Cue.NONE, screen.cue());
        assertEquals("liveness.progress.step", screen.statusKey());
        assertEquals(3, screen.stepIndex());
        assertEquals(0f, screen.ringProgress(), 0.0001f);
    }

    @Test
    public void tcAnd15_s13_cameraUnavailable_offersOpenSettingsRetryCancel() {
        LivenessScreenModel model = new LivenessScreenModel();
        model.onChallenge(new Challenge(ChallengeType.SMILE, 5000L), 1, 2);

        ScreenState screen = model.onStateChanged(SessionState.ERROR,
                new UserPrompt("liveness.error.camera_unavailable", PromptLevel.ERROR));

        assertEquals("liveness.error.camera_unavailable", screen.statusKey());
        assertEquals(ScreenState.Tone.DEVICE_ERROR, screen.tone());
        assertNull(screen.promptKey());
        assertEquals(0, screen.stepTotal());
        assertEquals(Arrays.asList(ScreenState.Button.OPEN_SETTINGS, ScreenState.Button.RETRY, ScreenState.Button.CANCEL),
                screen.buttons());
    }

    @Test
    public void tcUx_s14_engineError_offersOnlyClose() {
        ScreenState screen = new LivenessScreenModel().onStateChanged(SessionState.ERROR,
                new UserPrompt("liveness.error.engine", PromptLevel.ERROR));

        assertEquals("liveness.error.engine", screen.statusKey());
        assertEquals(ScreenState.Tone.FAILURE, screen.tone());
        assertEquals(Collections.singletonList(ScreenState.Button.CLOSE), screen.buttons());
    }

    @Test
    public void tcUx_s3_guidanceBeforeAnyStatus_fallsBackToThePositionStatus() {
        ScreenState screen = new LivenessScreenModel().onStateChanged(SessionState.WAITING_FOR_FACE,
                new UserPrompt("liveness.guidance.face_not_found", PromptLevel.GUIDANCE));

        assertEquals("liveness.status.position", screen.statusKey());
        assertEquals("liveness.guidance.face_not_found", screen.guidanceKey());
    }

    @Test
    public void tcUx_s3_guidanceDuringChallenge_keepsStepPromptAndRing() {
        LivenessScreenModel model = new LivenessScreenModel();
        model.onChallenge(new Challenge(ChallengeType.LOOK_UP, 5000L), 2, 3);
        model.onProgress(0.4f);

        ScreenState screen = model.onStateChanged(SessionState.ACTIVE_CHALLENGE,
                new UserPrompt("liveness.guidance.one_face_only", PromptLevel.GUIDANCE));

        assertEquals("liveness.guidance.one_face_only", screen.guidanceKey());
        assertEquals("liveness.progress.step", screen.statusKey());
        assertEquals(2, screen.stepIndex());
        assertEquals("liveness.prompt.look_up", screen.promptKey());
        assertEquals(ScreenState.Cue.UP, screen.cue());
        assertEquals(0.4f, screen.ringProgress(), 0.0001f);
    }

    @Test
    public void tcUx_s13_otherDeviceErrors_offerRetryCancelAndKeepTheStatus() {
        for (String key : new String[] {"liveness.error.device_unavailable", "liveness.error.device_connection",
                "liveness.error.invalid_frame"}) {
            LivenessScreenModel model = new LivenessScreenModel();
            model.onStateChanged(SessionState.WAITING_FOR_FACE, new UserPrompt("liveness.status.position", PromptLevel.INFO));

            ScreenState error = model.onStateChanged(SessionState.ERROR, new UserPrompt(key, PromptLevel.ERROR));
            ScreenState afterwards = model.onStateChanged(SessionState.WAITING_FOR_FACE,
                    new UserPrompt("liveness.guidance.face_not_found", PromptLevel.GUIDANCE));

            assertEquals(key, key, error.statusKey());
            assertEquals(key, ScreenState.Tone.DEVICE_ERROR, error.tone());
            assertEquals(key, Arrays.asList(ScreenState.Button.RETRY, ScreenState.Button.CANCEL), error.buttons());
            assertEquals(key, "liveness.status.position", afterwards.statusKey());
        }
    }

    @Test
    public void tcUx_progressBeforeAnyState_showsThePositionScreenWithTheRing() {
        ScreenState screen = new LivenessScreenModel().onProgress(0.3f);

        assertEquals("liveness.status.position", screen.statusKey());
        assertEquals(0.3f, screen.ringProgress(), 0.0001f);
    }

    @Test
    public void tcUx_promptArguments_reachTheStatusLine() {
        LivenessScreenModel model = new LivenessScreenModel();

        ScreenState info = model.onStateChanged(SessionState.WAITING_FOR_FACE,
                new UserPrompt("liveness.retry.available", PromptLevel.INFO, 2, 3));
        ScreenState step = model.onChallenge(new Challenge(ChallengeType.SMILE, 5000L), 2, 3);

        assertArrayEquals(new Object[] {2, 3}, info.statusArgs());
        assertArrayEquals(new Object[] {2, 3}, step.statusArgs());
    }
}
