package io.mosip.registration.livenessmanager.ui;

import java.util.Arrays;
import java.util.Collections;

import io.mosip.registration.liveness.api.Challenge;
import io.mosip.registration.liveness.api.ChallengeType;
import io.mosip.registration.liveness.api.PromptLevel;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.UserPrompt;

/**
 * Turns the liveness engine's callbacks into what {@code LivenessActivity} shows (UI_UX_SPEC §4, wireframes
 * M-1). Holds no Android types, so every state mapping is tested on the JVM. Not thread-safe; call it from
 * the UI thread.
 */
public final class LivenessScreenModel {

    /** Status line during the active phase: "Step {0} of {1}". */
    static final String STEP_KEY = "liveness.progress.step";

    /** Status before the engine names one: "Position your face inside the oval" (S2). */
    static final String POSITION_KEY = "liveness.status.position";

    /** Prompt during the passive check: "Look at the camera. No action needed." (UI_UX_SPEC §5.2). */
    static final String NO_ACTION_KEY = "liveness.status.no_action";

    /** LIV-003: camera missing or permission denied; the fix is in the system settings. */
    static final String CAMERA_UNAVAILABLE_KEY = "liveness.error.camera_unavailable";

    /** LIV-013: the engine cannot run (e.g. a model hash mismatch); nothing to retry. */
    static final String ENGINE_ERROR_KEY = "liveness.error.engine";

    private String statusKey = POSITION_KEY;
    private Object[] statusArgs = new Object[0];
    private ScreenState current;
    private SessionState lastState;

    /**
     * @param state  the engine's new state
     * @param prompt what the engine wants shown
     * @return the screen to show
     */
    public ScreenState onStateChanged(SessionState state, UserPrompt prompt) {
        // Within a passive check or a hold, the screen and its ring stay; only entering one starts it afresh
        boolean sameState = current != null && state == lastState;
        boolean keepScreen = current != null && (state == SessionState.ACTIVE_CHALLENGE
                || state == SessionState.NEUTRAL_HOLD || (state == SessionState.PASSIVE_CHECK && sameState));
        if (state == SessionState.PASSED || state == SessionState.FAILED) {
            // The engine has decided. LivenessActivity returns the result; Flutter shows S9 to S12 (UI_UX_SPEC §6.3)
            current = ScreenState.builder(prompt.key(),
                            state == SessionState.PASSED ? ScreenState.Tone.SUCCESS : ScreenState.Tone.FAILURE)
                    .statusArgs(prompt.args())
                    .buttons(Collections.<ScreenState.Button>emptyList())
                    .build();
        } else if (prompt.level() == PromptLevel.ERROR && CAMERA_UNAVAILABLE_KEY.equals(prompt.key())) {
            current = ScreenState.builder(prompt.key(), ScreenState.Tone.DEVICE_ERROR).statusArgs(prompt.args())
                    .buttons(Arrays.asList(ScreenState.Button.OPEN_SETTINGS, ScreenState.Button.RETRY,
                            ScreenState.Button.CANCEL))
                    .build();
        } else if (prompt.level() == PromptLevel.ERROR && ENGINE_ERROR_KEY.equals(prompt.key())) {
            current = ScreenState.builder(prompt.key(), ScreenState.Tone.FAILURE).statusArgs(prompt.args())
                    .buttons(Collections.singletonList(ScreenState.Button.CLOSE))
                    .build();
        } else if (prompt.level() == PromptLevel.ERROR) {
            // ERROR state with a device or frame error (LIV-001, 002, 012): the user can fix the cause and retry
            current = ScreenState.builder(prompt.key(), ScreenState.Tone.DEVICE_ERROR).statusArgs(prompt.args())
                    .buttons(Arrays.asList(ScreenState.Button.RETRY, ScreenState.Button.CANCEL))
                    .build();
        } else if (prompt.level() == PromptLevel.GUIDANCE && keepScreen) {
            current = ScreenState.Builder.from(current).guidance(prompt.key()).build();
        } else if (prompt.level() == PromptLevel.SUCCESS && current != null) {
            current = ScreenState.Builder.from(current).toast(prompt.key()).build();
        } else if (state == SessionState.NEUTRAL_HOLD && current != null && !sameState) {
            current = ScreenState.Builder.from(current)
                    .prompt(prompt.key(), ScreenState.Cue.NONE).ring(0f).guidance(null).build();
        } else if (keepScreen && state != SessionState.PASSIVE_CHECK) {
            // The engine announces each challenge with its prompt just before onChallenge, and INFO during a
            // challenge or the hold means the guidance is resolved: clear only the guidance, never make the
            // prompt a status
            current = ScreenState.Builder.from(current).guidance(null).build();
        } else if (prompt.level() == PromptLevel.GUIDANCE) {
            current = ScreenState.builder(statusKey, ScreenState.Tone.GUIDANCE).statusArgs(statusArgs)
                    .guidance(prompt.key()).build();
        } else {
            statusKey = prompt.key();
            statusArgs = prompt.args();
            ScreenState.Builder screen = ScreenState.builder(statusKey, ScreenState.Tone.INFO).statusArgs(statusArgs);
            if (state == SessionState.PASSIVE_CHECK) {
                screen.prompt(NO_ACTION_KEY, ScreenState.Cue.NONE).ring(sameState ? current.ringProgress() : 0f);
            }
            current = screen.build();
        }
        lastState = state;
        return current;
    }

    /**
     * @param challenge the action now requested
     * @param index     1-based position in the sequence
     * @param total     number of challenges in the sequence
     * @return the screen to show
     */
    public ScreenState onChallenge(Challenge challenge, int index, int total) {
        current = ScreenState.builder(STEP_KEY, ScreenState.Tone.INSTRUCTION)
                .statusArgs(index, total)
                .step(index, total)
                .prompt(challenge.promptKey(), cueFor(challenge.type()))
                .build();
        return current;
    }

    /**
     * @param fraction progress within the current step, 0..1
     * @return the current screen with the progress ring updated
     */
    public ScreenState onProgress(float fraction) {
        if (current == null) {
            current = ScreenState.builder(statusKey, ScreenState.Tone.INFO).statusArgs(statusArgs).build();
        }
        current = ScreenState.Builder.from(current).ring(fraction).build();
        return current;
    }

    private static ScreenState.Cue cueFor(ChallengeType type) {
        switch (type) {
            case TURN_LEFT:
                return ScreenState.Cue.LEFT;
            case TURN_RIGHT:
                return ScreenState.Cue.RIGHT;
            case LOOK_UP:
                return ScreenState.Cue.UP;
            case LOOK_DOWN:
                return ScreenState.Cue.DOWN;
            case SMILE:
                return ScreenState.Cue.SMILE;
            case OPEN_MOUTH:
                return ScreenState.Cue.MOUTH;
            case CLOSE_EYES:
            case BLINK:
                return ScreenState.Cue.EYES;
            default:
                return ScreenState.Cue.NONE;
        }
    }
}
