package io.mosip.registration.livenessmanager;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.VisibleForTesting;
import androidx.camera.view.PreviewView;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.mosip.registration.liveness.api.Challenge;
import io.mosip.registration.liveness.api.Decision;
import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.FrameListener;
import io.mosip.registration.liveness.api.LivenessCallback;
import io.mosip.registration.liveness.api.LivenessContext;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.api.LivenessPolicy;
import io.mosip.registration.liveness.api.LivenessResult;
import io.mosip.registration.liveness.api.LivenessSession;
import io.mosip.registration.liveness.api.PromptLevel;
import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.UserPrompt;
import io.mosip.registration.liveness.api.Workflow;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.liveness.engine.LivenessEngines;
import io.mosip.registration.livenessmanager.camera.CameraXBinder;
import io.mosip.registration.livenessmanager.camera.CameraXFrameSource;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;
import io.mosip.registration.livenessmanager.session.LivenessSessionStore;
import io.mosip.registration.livenessmanager.ui.LivenessOverlayView;
import io.mosip.registration.livenessmanager.ui.LivenessScreenModel;
import io.mosip.registration.livenessmanager.ui.MessageKeys;
import io.mosip.registration.livenessmanager.ui.ScreenState;

/**
 * The native liveness screen (UI_UX_SPEC §6.2, M-1); started through {@link LivenessContract}. Runs one engine
 * session on the front camera and finishes with the result summary. Device errors offer a retry inside the
 * screen, without using an attempt; the user's ✕, back and Cancel return CANCELLED.
 */
public class LivenessActivity extends ComponentActivity {

    /** How long the passed or failed screen stays before the activity returns (Flutter shows S9 to S12). */
    static final long FINAL_SCREEN_MS = 800L;

    /**
     * How long the camera may take to send its first frame before the screen shows LIV-003: a camera in use or
     * blocked by policy binds without error but sends nothing (like the 2 s rule for SBI streams).
     */
    static final long FIRST_FRAME_TIMEOUT_MS = 3000L;

    /** How long the S6 feedback toast stays. */
    private static final long TOAST_MS = 1500L;

    private static final String CLIENT_RETRY_LIMIT_KEY = "mosip.registration.num_of_face_retries";
    private static final String DIAGNOSTICS_KEY = LivenessConfigLoader.PREFIX + "diagnostics.enabled";
    private static final String POSITION_KEY = "liveness.status.position";

    /** Makes the camera binder for each session; tests replace it, since Robolectric has no camera. */
    interface BinderFactory {
        CameraXFrameSource.CameraBinder create(LivenessActivity activity, PreviewView preview);
    }

    static final BinderFactory CAMERAX_BINDER = CameraXBinder::new;

    @VisibleForTesting
    static BinderFactory binderFactory = CAMERAX_BINDER;

    private final LivenessSessionStore store = LivenessSessionStore.shared();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideToast = () -> this.toast.setVisibility(View.GONE);

    private ActivityResultLauncher<String> permissionRequest;
    private LivenessContract.Request request;
    private Workflow workflow;
    private LivenessPolicy policy;
    private boolean diagnostics;
    private int attempt;

    private LivenessScreenModel model = new LivenessScreenModel();
    private Callback callback;
    private LivenessSession session;
    private CameraXFrameSource source;
    /** An engine ERROR result waiting for the user's Retry, Cancel or Close. */
    private LivenessResult pendingError;
    /** The recorded pass or fail, shown on the final screen; back returns it rather than a cancel. */
    private LivenessResultSummary recorded;
    private Runnable firstFrameCheck;
    private AlertDialog rationale;
    private boolean awaitingSettings;
    private boolean finished;

    private PreviewView preview;
    private LivenessOverlayView overlay;
    private TextView status;
    private TextView guidance;
    private TextView step;
    private TextView cue;
    private TextView prompt;
    private TextView toast;
    private ProgressBar timeBar;
    private ObjectAnimator timeBarAnimator;
    private View dismiss;
    private View cancelButton;
    private View openSettingsButton;
    private View retryButton;
    private View closeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionRequest = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startSession();
                    } else {
                        showDeviceError(LivenessErrorCode.CAMERA_UNAVAILABLE);
                    }
                });
        try {
            request = LivenessContract.parseRequest(getIntent());
            workflow = Workflow.valueOf(request.workflow());
        } catch (IllegalArgumentException e) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        policy = LivenessConfigLoader.load(request.config(), workflow);
        diagnostics = "true".equalsIgnoreCase(request.config().get(DIAGNOSTICS_KEY));
        attempt = store.begin(request.workflow(), request.fieldId(), policy, clientRetryLimit());
        LivenessResultSummary limit = store.limitReached(request.workflow(), request.fieldId());
        if (limit != null) {
            // Attempts used up: no new session, not even the camera (LIV-011)
            finished = true;
            setResult(RESULT_OK, LivenessContract.resultIntent(limit));
            finish();
            return;
        }

        setContentView(R.layout.liveness_activity);
        preview = findViewById(R.id.liveness_preview);
        overlay = findViewById(R.id.liveness_overlay);
        status = findViewById(R.id.liveness_status);
        guidance = findViewById(R.id.liveness_guidance);
        step = findViewById(R.id.liveness_step);
        cue = findViewById(R.id.liveness_cue);
        prompt = findViewById(R.id.liveness_prompt);
        toast = findViewById(R.id.liveness_toast);
        timeBar = findViewById(R.id.liveness_time_bar);
        dismiss = findViewById(R.id.liveness_dismiss);
        cancelButton = findViewById(R.id.liveness_cancel);
        openSettingsButton = findViewById(R.id.liveness_open_settings);
        retryButton = findViewById(R.id.liveness_retry);
        closeButton = findViewById(R.id.liveness_close);

        dismiss.setOnClickListener(v -> cancel());
        cancelButton.setOnClickListener(v -> cancel());
        retryButton.setOnClickListener(v -> retry());
        closeButton.setOnClickListener(v -> finishWith(pendingError));
        openSettingsButton.setOnClickListener(v -> openSettings());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                cancel();
            }
        });

        render(model.onStateChanged(SessionState.WAITING_FOR_FACE, new UserPrompt(POSITION_KEY, PromptLevel.INFO)));
        if (hasCameraPermission()) {
            startSession();
        } else {
            askForCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingSettings && hasCameraPermission()) {
            awaitingSettings = false;
            startSession();
        }
    }

    @Override
    protected void onDestroy() {
        if (rationale != null) {
            rationale.dismiss();
        }
        stopSession();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** The §6.6 rationale before the system permission dialog. */
    private void askForCamera() {
        rationale = new AlertDialog.Builder(this)
                .setTitle(text("liveness.permission.title"))
                .setMessage(text("liveness.permission.body"))
                .setNegativeButton(text("liveness.permission.not_now"),
                        (d, w) -> showDeviceError(LivenessErrorCode.CAMERA_UNAVAILABLE))
                .setPositiveButton(text("liveness.permission.allow"),
                        (d, w) -> permissionRequest.launch(Manifest.permission.CAMERA))
                .setCancelable(false)
                .show();
    }

    private void startSession() {
        stopSession();
        pendingError = null;
        model = new LivenessScreenModel();
        render(model.onStateChanged(SessionState.WAITING_FOR_FACE, new UserPrompt(POSITION_KEY, PromptLevel.INFO)));
        Callback sessionCallback = new Callback();
        CameraXFrameSource frameSource = new CameraXFrameSource(this, binderFactory.create(this, preview));
        LivenessSession started = LivenessEngines.stub(request.config()).startSession(
                LivenessContext.builder().workflow(workflow).attempt(attempt).source(frameSource.capabilities())
                        .build(),
                policy, sessionCallback);
        if (!sessionCallback.active) {
            // The engine finished while starting (for example LIV-013); its result is already handled
            return;
        }
        callback = sessionCallback;
        session = started;
        source = frameSource;
        AtomicBoolean framesSeen = new AtomicBoolean();
        firstFrameCheck = () -> {
            if (callback == sessionCallback && !framesSeen.get()) {
                showDeviceError(LivenessErrorCode.CAMERA_UNAVAILABLE);
            }
        };
        handler.postDelayed(firstFrameCheck, FIRST_FRAME_TIMEOUT_MS);
        try {
            frameSource.start(new FrameListener() {
                @Override
                public void onFrame(FaceFrame frame) {
                    framesSeen.set(true);
                    started.onFrame(frame);
                }

                @Override
                public void onError(LivenessErrorCode code, String detail) {
                    runOnUiThread(() -> {
                        if (callback == sessionCallback) {
                            showDeviceError(code);
                        }
                    });
                }

                @Override
                public void onEnd() {
                }
            });
        } catch (LivenessException e) {
            showDeviceError(e.errorCode());
        }
    }

    /** Stops the camera and the session; the session's own CANCELLED result is ignored. */
    private void stopSession() {
        if (firstFrameCheck != null) {
            handler.removeCallbacks(firstFrameCheck);
            firstFrameCheck = null;
        }
        if (callback != null) {
            callback.active = false;
            callback = null;
        }
        if (source != null) {
            source.stop();
            source = null;
        }
        if (session != null) {
            session.cancel();
            session = null;
        }
        stopTimeBar();
    }

    /** S13: a device problem the user can fix, then retry without using an attempt. */
    private void showDeviceError(LivenessErrorCode code) {
        stopSession();
        render(model.onStateChanged(SessionState.ERROR, new UserPrompt(code.messageKey(), PromptLevel.ERROR)));
    }

    private void retry() {
        if (hasCameraPermission()) {
            startSession();
        } else {
            permissionRequest.launch(Manifest.permission.CAMERA);
        }
    }

    private void openSettings() {
        awaitingSettings = true;
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    /** The user's ✕, back or Cancel; once a pass or fail is recorded, that result is returned instead. */
    private void cancel() {
        if (recorded != null) {
            finishWith(recorded);
        } else {
            finishWith(LivenessResult.builder(SessionState.CANCELLED, Decision.INCONCLUSIVE).build());
        }
    }

    private void onResult(LivenessResult result) {
        session = null;
        if (source != null) {
            source.stop();
            source = null;
        }
        stopTimeBar();
        switch (result.finalState()) {
            case PASSED:
            case FAILED:
                // Record now, so the attempt counts even if the activity dies during the final screen
                recorded = complete(result);
                handler.postDelayed(() -> finishWith(recorded), FINAL_SCREEN_MS);
                break;
            case ERROR:
                pendingError = result;
                break;
            default:
                finishWith(result);
        }
    }

    private LivenessResultSummary complete(LivenessResult result) {
        return store.complete(request.workflow(), request.fieldId(), result, diagnostics);
    }

    private void finishWith(LivenessResult result) {
        if (!finished) {
            finishWith(complete(result == null ? LivenessResult.builder(SessionState.CANCELLED,
                    Decision.INCONCLUSIVE).build() : result));
        }
    }

    private void finishWith(LivenessResultSummary summary) {
        if (finished) {
            return;
        }
        finished = true;
        handler.removeCallbacksAndMessages(null);
        stopSession();
        setResult(RESULT_OK, LivenessContract.resultIntent(summary));
        finish();
    }

    private void render(ScreenState screen) {
        status.setText(text(screen.statusKey(), screen.statusArgs()));
        status.setTextColor(getColor(colorFor(screen.tone())));
        show(guidance, screen.guidanceKey() == null ? null : text(screen.guidanceKey()));
        show(prompt, screen.promptKey() == null ? null : text(screen.promptKey()));
        show(cue, glyphFor(screen.cue()));
        show(step, screen.stepTotal() > 0 ? dots(screen.stepIndex(), screen.stepTotal()) : null);
        if (screen.stepTotal() > 0) {
            step.setContentDescription(text("liveness.progress.step", screen.stepIndex(), screen.stepTotal()));
        }
        overlay.show(screen.ringProgress(), getColor(colorFor(screen.tone())));
        if (screen.toastKey() != null) {
            show(toast, text(screen.toastKey()));
            handler.removeCallbacks(hideToast);
            handler.postDelayed(hideToast, TOAST_MS);
        }
        if (screen.tone() != ScreenState.Tone.INSTRUCTION) {
            stopTimeBar();
        }
        List<ScreenState.Button> buttons = screen.buttons();
        boolean row = buttons.contains(ScreenState.Button.OPEN_SETTINGS) || buttons.contains(ScreenState.Button.RETRY)
                || buttons.contains(ScreenState.Button.CLOSE);
        visible(dismiss, buttons.contains(ScreenState.Button.CANCEL) && !row);
        visible(cancelButton, buttons.contains(ScreenState.Button.CANCEL) && row);
        visible(openSettingsButton, buttons.contains(ScreenState.Button.OPEN_SETTINGS));
        visible(retryButton, buttons.contains(ScreenState.Button.RETRY));
        visible(closeButton, buttons.contains(ScreenState.Button.CLOSE));
    }

    /** The time bar empties over the challenge's time limit. */
    private void startTimeBar(long timeoutMs) {
        stopTimeBar();
        timeBar.setVisibility(View.VISIBLE);
        timeBar.setProgress(timeBar.getMax());
        timeBarAnimator = ObjectAnimator.ofInt(timeBar, "progress", timeBar.getMax(), 0);
        timeBarAnimator.setDuration(timeoutMs);
        timeBarAnimator.setInterpolator(new LinearInterpolator());
        timeBarAnimator.start();
    }

    private void stopTimeBar() {
        if (timeBarAnimator != null) {
            timeBarAnimator.cancel();
            timeBarAnimator = null;
        }
        if (timeBar != null) {
            timeBar.setVisibility(View.GONE);
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private int clientRetryLimit() {
        try {
            String value = request.config().get(CLIENT_RETRY_LIMIT_KEY);
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String text(String key, Object... args) {
        return MessageKeys.text(this, key, args);
    }

    private String glyphFor(ScreenState.Cue screenCue) {
        switch (screenCue) {
            case LEFT:
                return getString(R.string.liveness_cue_left);
            case RIGHT:
                return getString(R.string.liveness_cue_right);
            case UP:
                return getString(R.string.liveness_cue_up);
            case DOWN:
                return getString(R.string.liveness_cue_down);
            case SMILE:
                return getString(R.string.liveness_cue_smile);
            case MOUTH:
                return getString(R.string.liveness_cue_mouth);
            case EYES:
                return getString(R.string.liveness_cue_eyes);
            default:
                return null;
        }
    }

    private static int colorFor(ScreenState.Tone tone) {
        switch (tone) {
            case GUIDANCE:
                return R.color.liveness_guidance;
            case SUCCESS:
                return R.color.liveness_success;
            case FAILURE:
                return R.color.liveness_failure;
            case DEVICE_ERROR:
                return R.color.liveness_device_error;
            case INSTRUCTION:
                return R.color.liveness_instruction;
            default:
                return R.color.liveness_info;
        }
    }

    /** Step dots, for example "● ● ○" for step 2 of 3. */
    private static String dots(int index, int total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= total; i++) {
            sb.append(i <= index ? '●' : '○');
            if (i < total) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static void show(TextView view, String value) {
        view.setText(value);
        view.setVisibility(value == null ? View.GONE : View.VISIBLE);
    }

    private static void visible(View view, boolean shown) {
        view.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** Engine callbacks for one session, moved to the main thread; inactive once the session is replaced. */
    private final class Callback implements LivenessCallback {
        private volatile boolean active = true;

        @Override
        public void onStateChanged(SessionState state, UserPrompt userPrompt) {
            runOnUiThread(() -> {
                if (active) {
                    render(model.onStateChanged(state, userPrompt));
                }
            });
        }

        @Override
        public void onChallenge(Challenge challenge, int index, int total) {
            runOnUiThread(() -> {
                if (active) {
                    render(model.onChallenge(challenge, index, total));
                    startTimeBar(challenge.timeoutMs());
                }
            });
        }

        @Override
        public void onProgress(float fraction) {
            runOnUiThread(() -> {
                if (active) {
                    render(model.onProgress(fraction));
                }
            });
        }

        @Override
        public void onResult(LivenessResult result) {
            runOnUiThread(() -> {
                if (active) {
                    active = false;
                    callback = null;
                    LivenessActivity.this.onResult(result);
                }
            });
        }
    }
}
