package io.mosip.registration.livenessmanager.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the liveness screen shows at one moment (UI_UX_SPEC §4 states). Text is held as message keys
 * (API.md §7); the view resolves them. Immutable.
 */
public final class ScreenState {

    /** Colour and icon group of the status line (UI_UX_SPEC §3.2). */
    public enum Tone { INFO, INSTRUCTION, GUIDANCE, SUCCESS, FAILURE, DEVICE_ERROR }

    /** Icon beside the prompt (UI_UX_SPEC §3.1 direction cue). */
    public enum Cue { NONE, LEFT, RIGHT, UP, DOWN, SMILE, MOUTH, EYES }

    /** Buttons the screen may offer. */
    public enum Button { CANCEL, RETRY, OPEN_SETTINGS, CLOSE }

    private final String statusKey;
    private final Object[] statusArgs;
    private final Tone tone;
    private final String promptKey;
    private final String guidanceKey;
    private final Cue cue;
    private final int stepIndex;
    private final int stepTotal;
    private final float ringProgress;
    private final String toastKey;
    private final List<Button> buttons;

    private ScreenState(Builder b) {
        this.statusKey = b.statusKey;
        this.statusArgs = b.statusArgs.clone();
        this.tone = b.tone;
        this.promptKey = b.promptKey;
        this.guidanceKey = b.guidanceKey;
        this.cue = b.cue;
        this.stepIndex = b.stepIndex;
        this.stepTotal = b.stepTotal;
        this.ringProgress = b.ringProgress;
        this.toastKey = b.toastKey;
        this.buttons = Collections.unmodifiableList(new ArrayList<>(b.buttons));
    }

    /** @return message key of the status line, or null */
    public String statusKey() {
        return statusKey;
    }

    /** @return arguments for the status message, e.g. {@code Step {0} of {1}}; a copy, never null */
    public Object[] statusArgs() {
        return statusArgs.clone();
    }

    /** @return colour and icon group of the status line */
    public Tone tone() {
        return tone;
    }

    /** @return message key of the large prompt, or null when there is no prompt */
    public String promptKey() {
        return promptKey;
    }

    /** @return message key of the fixable guidance shown under the status (S3), or null */
    public String guidanceKey() {
        return guidanceKey;
    }

    /** @return icon beside the prompt */
    public Cue cue() {
        return cue;
    }

    /** @return current challenge, 1-based; 0 outside the active phase */
    public int stepIndex() {
        return stepIndex;
    }

    /** @return number of challenges; 0 outside the active phase (the total is shown only once it starts) */
    public int stepTotal() {
        return stepTotal;
    }

    /** @return progress ring around the oval, 0..1 within the current step */
    public float ringProgress() {
        return ringProgress;
    }

    /** @return message key of the short feedback toast (S6), or null */
    public String toastKey() {
        return toastKey;
    }

    /** @return buttons shown, in order; unmodifiable */
    public List<Button> buttons() {
        return buttons;
    }

    static Builder builder(String statusKey, Tone tone) {
        return new Builder(statusKey, tone);
    }

    /** Mutable builder; unset fields mean "not shown". */
    static final class Builder {
        private final String statusKey;
        private final Tone tone;
        private Object[] statusArgs = new Object[0];
        private String promptKey;
        private String guidanceKey;
        private Cue cue = Cue.NONE;
        private int stepIndex;
        private int stepTotal;
        private float ringProgress;
        private String toastKey;
        private List<Button> buttons = Collections.singletonList(Button.CANCEL);

        private Builder(String statusKey, Tone tone) {
            this.statusKey = statusKey;
            this.tone = tone;
        }

        Builder statusArgs(Object... args) {
            this.statusArgs = args == null ? new Object[0] : args;
            return this;
        }

        Builder prompt(String key, Cue cue) {
            this.promptKey = key;
            this.cue = cue;
            return this;
        }

        Builder guidance(String key) {
            this.guidanceKey = key;
            return this;
        }

        Builder step(int index, int total) {
            this.stepIndex = index;
            this.stepTotal = total;
            return this;
        }

        Builder ring(float progress) {
            this.ringProgress = progress;
            return this;
        }

        Builder toast(String key) {
            this.toastKey = key;
            return this;
        }

        Builder buttons(List<Button> value) {
            this.buttons = value;
            return this;
        }

        /** Starts from what {@code screen} shows, to change one part of it. */
        static Builder from(ScreenState screen) {
            Builder b = new Builder(screen.statusKey, screen.tone);
            b.statusArgs = screen.statusArgs;
            b.promptKey = screen.promptKey;
            b.guidanceKey = screen.guidanceKey;
            b.cue = screen.cue;
            b.stepIndex = screen.stepIndex;
            b.stepTotal = screen.stepTotal;
            b.ringProgress = screen.ringProgress;
            b.buttons = screen.buttons;
            return b;
        }

        ScreenState build() {
            return new ScreenState(this);
        }
    }
}
