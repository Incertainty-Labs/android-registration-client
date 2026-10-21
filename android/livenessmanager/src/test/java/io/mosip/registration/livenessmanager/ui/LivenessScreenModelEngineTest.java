package io.mosip.registration.livenessmanager.ui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mosip.registration.liveness.api.Challenge;
import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.LivenessCallback;
import io.mosip.registration.liveness.api.LivenessContext;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.UserPrompt;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.liveness.engine.LivenessEngines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Drives liveness-core's stub engine through the model, in the engine's real callback order. */
public class LivenessScreenModelEngineTest {

    private static final long FRAME_INTERVAL_MS = 100L;
    private static final long MAX_SESSION_MS = 60_000L;

    @Test
    public void passActive_neverShowsAChallengePromptAsStatusAndEndsInSuccess() {
        List<ScreenState> screens = run("PASS_ACTIVE");

        for (ScreenState screen : screens) {
            assertNotNull("blank status", screen.statusKey());
            assertFalse("challenge prompt shown as status: " + screen.statusKey(),
                    screen.statusKey().startsWith("liveness.prompt."));
        }
        assertTrue(screens.stream().anyMatch(s -> s.stepTotal() > 0 && s.promptKey() != null
                && s.promptKey().startsWith("liveness.prompt.")));
        ScreenState last = screens.get(screens.size() - 1);
        assertEquals(ScreenState.Tone.SUCCESS, last.tone());
        assertTrue(last.buttons().isEmpty());
    }

    @Test
    public void noFace_keepsThePositionStatusAndEndsInFailureWithoutDeviceButtons() {
        List<ScreenState> screens = run("NO_FACE");

        assertTrue(screens.stream().anyMatch(s -> "liveness.guidance.face_not_found".equals(s.guidanceKey())
                && "liveness.status.position".equals(s.statusKey())));
        ScreenState last = screens.get(screens.size() - 1);
        assertEquals(ScreenState.Tone.FAILURE, last.tone());
        assertTrue(last.buttons().isEmpty());
    }

    @Test
    public void pad_endsWithTheGenericFailureAndNoRetry() {
        List<ScreenState> screens = run("PAD");

        ScreenState last = screens.get(screens.size() - 1);
        assertEquals("liveness.fail.generic", last.statusKey());
        assertEquals(ScreenState.Tone.FAILURE, last.tone());
        assertTrue(last.buttons().isEmpty());
    }

    /** Runs one stub scenario to its result and returns every screen the model produced, in order. */
    private static List<ScreenState> run(String scenario) {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigLoader.PREFIX + "enabled", "true");
        config.put(LivenessConfigLoader.PREFIX + "stub.force", scenario);
        LivenessEngine engine = LivenessEngines.stub(config);
        Recorder recorder = new Recorder();
        LivenessSession session = engine.startSession(
                LivenessContext.builder().workflow(Workflow.RESIDENT_REGISTRATION).build(),
                LivenessConfigLoader.load(config, Workflow.RESIDENT_REGISTRATION), recorder);
        long sequence = 0;
        for (long t = 0; recorder.result == null && t < MAX_SESSION_MS; t += FRAME_INTERVAL_MS) {
            session.onFrame(new FaceFrame(2, 2, new byte[12], t, sequence++, "test:stub"));
        }
        assertNotNull(scenario + " never finished", recorder.result);
        return recorder.screens;
    }

    private static final class Recorder implements LivenessCallback {
        private final LivenessScreenModel model = new LivenessScreenModel();
        private final List<ScreenState> screens = new ArrayList<>();
        private LivenessResult result;

        @Override
        public void onStateChanged(SessionState state, UserPrompt prompt) {
            screens.add(model.onStateChanged(state, prompt));
        }

        @Override
        public void onChallenge(Challenge challenge, int index, int total) {
            screens.add(model.onChallenge(challenge, index, total));
        }

        @Override
        public void onProgress(float fraction) {
            screens.add(model.onProgress(fraction));
        }

        @Override
        public void onResult(LivenessResult result) {
            this.result = result;
        }
    }
}
