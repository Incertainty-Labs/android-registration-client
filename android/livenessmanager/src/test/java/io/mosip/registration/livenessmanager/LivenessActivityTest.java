package io.mosip.registration.livenessmanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.mosip.registration.liveness.api.SessionState;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.engine.LivenessEngines;
import io.mosip.registration.livenessmanager.engine.EngineHolder;
import io.mosip.registration.livenessmanager.camera.CameraXFrameSource;
import io.mosip.registration.livenessmanager.result.LivenessResultSummary;
import io.mosip.registration.livenessmanager.session.LivenessSessionStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LivenessActivityTest {

    private static final String WORKFLOW = "RESIDENT_REGISTRATION";
    private static final String FACE = "individualBiometrics";
    private static final long FRAME_INTERVAL_MS = 100L;
    private static final int MAX_FRAMES = 600;

    private Application app;
    private FakeBinder binder;
    private long nextFrameMs;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        binder = new FakeBinder();
        LivenessActivity.binderFactory = (activity, preview) -> binder;
        LivenessActivity.engines = EngineHolder.shared();
        LivenessSessionStore.shared().reset(WORKFLOW, FACE);
    }

    @After
    public void tearDown() {
        LivenessActivity.binderFactory = LivenessActivity.CAMERAX_BINDER;
        LivenessActivity.engines = EngineHolder.shared();
        LivenessSessionStore.shared().reset(WORKFLOW, FACE);
    }

    @Test
    public void tcAnd15_permissionNotGranted_asksWithTheRationaleFirst() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);

        launch("PASS_PASSIVE");

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Camera access needed", Shadows.shadowOf(dialog).getTitle().toString());
    }

    @Test
    public void tcAnd15_notNow_showsCameraUnavailableWithOpenSettings() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        LivenessActivity activity = launch("PASS_PASSIVE").get();

        click(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_NEGATIVE);

        assertEquals("Camera is not available.", text(activity, R.id.liveness_status));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.liveness_open_settings).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.liveness_retry).getVisibility());
    }

    @Test
    public void tcAnd_cancelOnCameraUnavailable_returnsCancelled() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        LivenessActivity activity = launch("PASS_PASSIVE").get();
        click(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_NEGATIVE);

        activity.findViewById(R.id.liveness_cancel).performClick();

        assertEquals("CANCELLED", result(activity).finalState());
    }

    @Test
    public void tcAnd15_bindFailure_showsCameraUnavailable() {
        binder.failure = new IllegalStateException("No front camera");

        LivenessActivity activity = launch("PASS_PASSIVE").get();

        assertEquals("Camera is not available.", text(activity, R.id.liveness_status));
        assertFalse(activity.isFinishing());
    }

    @Test
    public void tcAnd_passingSession_returnsPassedAfterTheFinalScreen() {
        LivenessActivity activity = launch("PASS_PASSIVE").get();

        runUntilResult();
        assertFalse("final screen stays briefly", activity.isFinishing());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FINAL_SCREEN_MS));

        LivenessResultSummary summary = result(activity);
        assertEquals("PASSED", summary.finalState());
        assertEquals(1, summary.attempt());
        assertNotNull(LivenessSessionStore.shared().lastResult(WORKFLOW, FACE));
    }

    @Test
    public void tcAnd_failedChallenge_usesAnAttempt() {
        LivenessActivity activity = launch("FAIL_CHALLENGE").get();

        runUntilResult();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FINAL_SCREEN_MS));

        LivenessResultSummary summary = result(activity);
        assertEquals("FAILED", summary.finalState());
        assertEquals("LIV-009", summary.errorCode());
        assertTrue(summary.retryAllowed());
    }

    @Test
    public void tcAnd_backDuringSession_returnsCancelled() {
        LivenessActivity activity = launch("PASS_ACTIVE").get();
        frames(3);

        activity.getOnBackPressedDispatcher().onBackPressed();

        assertEquals("CANCELLED", result(activity).finalState());
    }

    @Test
    public void tcAnd_closeButton_returnsCancelled() {
        LivenessActivity activity = launch("PASS_ACTIVE").get();
        frames(3);

        activity.findViewById(R.id.liveness_dismiss).performClick();

        assertEquals("CANCELLED", result(activity).finalState());
    }

    @Test
    public void tcUx_s14_engineError_offersOnlyCloseAndReturnsTheError() {
        LivenessActivity activity = launch("ENGINE_ERROR").get();
        View close = activity.findViewById(R.id.liveness_close);

        for (int i = 0; i < MAX_FRAMES && close.getVisibility() != View.VISIBLE; i++) {
            frames(1);
        }

        assertEquals(View.VISIBLE, close.getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.liveness_retry).getVisibility());
        activity.findViewById(R.id.liveness_close).performClick();
        LivenessResultSummary summary = result(activity);
        assertEquals("ERROR", summary.finalState());
        assertEquals("LIV-013", summary.errorCode());
    }

    @Test
    public void tcUx_s13_unreadableFrames_offerRetryWhichStartsAgain() {
        LivenessActivity activity = launch("PASS_PASSIVE").get();
        for (int i = 0; i < 5; i++) {
            binder.analyzer.analyze(image(new byte[3]));
        }
        assertEquals("The camera sent unreadable images. Please try again.", text(activity, R.id.liveness_status));
        assertFalse(activity.isFinishing());

        activity.findViewById(R.id.liveness_retry).performClick();
        runUntilResult();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FINAL_SCREEN_MS));

        LivenessResultSummary summary = result(activity);
        assertEquals("PASSED", summary.finalState());
        assertEquals(1, summary.attempt());
    }

    @Test
    public void tcAnd_backDuringTheFinalScreen_keepsThePass() {
        LivenessActivity activity = launch("PASS_PASSIVE").get();
        runUntilResult();

        activity.getOnBackPressedDispatcher().onBackPressed();

        assertEquals("PASSED", result(activity).finalState());
        assertEquals(SessionState.PASSED, LivenessSessionStore.shared().lastResult(WORKFLOW, FACE).finalState());
    }

    @Test
    public void tcAnd_backDuringTheFinalScreen_keepsTheFailureAndCountsItOnce() {
        LivenessActivity activity = launch("FAIL_CHALLENGE").get();
        runUntilResult();

        activity.getOnBackPressedDispatcher().onBackPressed();

        assertEquals("FAILED", result(activity).finalState());
        assertEquals(2, LivenessSessionStore.shared().begin(WORKFLOW, FACE, null, 0));
    }

    @Test
    public void tcAnd15_noFrameInTime_showsCameraUnavailable() {
        LivenessActivity activity = launch("PASS_PASSIVE").get();

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FIRST_FRAME_TIMEOUT_MS));

        assertEquals("Camera is not available.", text(activity, R.id.liveness_status));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.liveness_retry).getVisibility());
    }

    @Test
    public void tcAnd15_framesInTime_keepTheSessionRunning() {
        LivenessActivity activity = launch("PASS_ACTIVE").get();
        frames(1);

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FIRST_FRAME_TIMEOUT_MS));

        assertFalse("Camera is not available.".equals(text(activity, R.id.liveness_status)));
    }

    @Test
    public void tcAnd15_laterBindFailure_showsCameraUnavailable() {
        LivenessActivity activity = launch("PASS_PASSIVE").get();

        binder.onFailure.accept(new IllegalStateException("Camera in use"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Camera is not available.", text(activity, R.id.liveness_status));
    }

    @Test
    public void tcAnd15_allowThenGranted_startsTheSession() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        LivenessActivity activity = launch("PASS_PASSIVE").get();

        click(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_POSITIVE);
        grantRequestedCamera(activity);

        assertNotNull("camera not bound", binder.analyzer);
    }

    @Test
    public void tcAnd15_returningFromSettingsWithThePermission_startsTheSession() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        ActivityController<LivenessActivity> controller = launch("PASS_PASSIVE");
        LivenessActivity activity = controller.get();
        click(ShadowAlertDialog.getLatestAlertDialog(), DialogInterface.BUTTON_NEGATIVE);

        activity.findViewById(R.id.liveness_open_settings).performClick();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Shadows.shadowOf(activity).getNextStartedActivity().getAction());
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        controller.pause().resume();

        assertNotNull("camera not bound", binder.analyzer);
    }

    @Test
    public void tcAnd_destroy_dismissesTheRationale() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        ActivityController<LivenessActivity> controller = launch("PASS_PASSIVE");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();

        controller.pause().stop().destroy();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void tcAnd_attemptsUsedUp_returnsMaxRetriesWithoutTheCamera() {
        LivenessActivity first = launch("FAIL_CHALLENGE", "1").get();
        runUntilResult();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FINAL_SCREEN_MS));
        assertEquals("FAILED", result(first).finalState());
        binder.analyzer = null;

        LivenessActivity second = launch("PASS_ACTIVE", "1").get();

        LivenessResultSummary summary = result(second);
        assertEquals("LIV-011", summary.errorCode());
        assertFalse(summary.retryAllowed());
        assertNull("camera bound", binder.analyzer);
    }

    /** Stub engine: checks the activity and camera plumbing; re-run with the real engine in B-07. */
    @Test
    public void tcSec02_aWholeSession_writesNoFramesToDisk() {
        Map<String, String> before = filesUnder(app.getCacheDir(), app.getFilesDir(), app.getNoBackupFilesDir());

        LivenessActivity activity = launch("PASS_ACTIVE").get();
        runUntilResult();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FINAL_SCREEN_MS));
        assertEquals("PASSED", result(activity).finalState());

        assertEquals(before, filesUnder(app.getCacheDir(), app.getFilesDir(), app.getNoBackupFilesDir()));
    }

    /** Every file under the roots, with its size and last change, so an overwrite or an append shows too. */
    private static Map<String, String> filesUnder(File... roots) {
        Map<String, String> files = new TreeMap<>();
        ArrayDeque<File> todo = new ArrayDeque<>(Arrays.asList(roots));
        while (!todo.isEmpty()) {
            File file = todo.pop();
            File[] children = file.listFiles();
            if (children == null) {
                files.put(file.getAbsolutePath(), file.length() + ":" + file.lastModified());
            } else {
                todo.addAll(Arrays.asList(children));
            }
        }
        return files;
    }

    @Test
    public void tcEng_withoutAForcedScenario_usesTheAppsEngine() {
        EngineHolder holder = new EngineHolder();
        holder.start(Runnable::run, () -> LivenessEngines.stub(
                Collections.singletonMap("mosip.registration.face.liveness.stub.force", "PASS_PASSIVE")));
        LivenessActivity.engines = holder;
        LivenessActivity activity = launch(null).get();

        runUntilResult();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LivenessActivity.FINAL_SCREEN_MS));

        assertEquals("PASSED", result(activity).finalState());
    }

    @Test
    public void tcEng_engineStillLoading_startsTheSessionOnceReady() {
        java.util.List<Runnable> loading = new java.util.ArrayList<>();
        EngineHolder holder = new EngineHolder();
        holder.start(loading::add, () -> LivenessEngines.stub(Collections.emptyMap()));
        LivenessActivity.engines = holder;
        launch(null).get();
        assertNull("camera bound before the engine", binder.analyzer);

        loading.remove(0).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull("camera not bound", binder.analyzer);
    }

    @Test
    public void tcEng_engineReadyAfterTheScreenIsGone_startsNoSession() {
        java.util.List<Runnable> loading = new java.util.ArrayList<>();
        EngineHolder holder = new EngineHolder();
        holder.start(loading::add, () -> LivenessEngines.stub(Collections.emptyMap()));
        LivenessActivity.engines = holder;
        ActivityController<LivenessActivity> controller = launch(null);
        controller.pause().stop().destroy();

        loading.remove(0).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("a session started on a destroyed screen", binder.analyzer);
    }

    @Test
    public void tcEng_engineThatFailsToLoad_offersOnlyCloseAndReturnsLiv013() {
        EngineHolder holder = new EngineHolder();
        holder.start(Runnable::run, () -> {
            throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, "model hash mismatch");
        });
        LivenessActivity.engines = holder;
        LivenessActivity activity = launch(null).get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.liveness_close).getVisibility());
        assertNull("camera bound without an engine", binder.analyzer);
        activity.findViewById(R.id.liveness_close).performClick();
        LivenessResultSummary summary = result(activity);
        assertEquals("ERROR", summary.finalState());
        assertEquals("LIV-013", summary.errorCode());
    }

    @Test
    public void tcUx_s14_backOnTheEngineErrorScreen_alsoReturnsLiv013() {
        EngineHolder holder = new EngineHolder();
        holder.start(Runnable::run, () -> {
            throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, "model hash mismatch");
        });
        LivenessActivity.engines = holder;
        LivenessActivity activity = launch(null).get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        activity.getOnBackPressedDispatcher().onBackPressed();

        LivenessResultSummary summary = result(activity);
        assertEquals("ERROR", summary.finalState());
        assertEquals("LIV-013", summary.errorCode());
    }

    @Test
    public void tcAnd_requestWithoutWorkflow_finishesWithoutAResult() {
        Intent intent = new Intent(app, LivenessActivity.class);

        LivenessActivity activity = Robolectric.buildActivity(LivenessActivity.class, intent).setup().get();

        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
        assertNull(Shadows.shadowOf(activity).getResultIntent());
    }

    private ActivityController<LivenessActivity> launch(String scenario) {
        return launch(scenario, null);
    }

    private ActivityController<LivenessActivity> launch(String scenario, String maxRetries) {
        Map<String, String> config = new HashMap<>();
        config.put("mosip.registration.face.liveness.enabled", "true");
        if (maxRetries != null) {
            config.put("mosip.registration.face.liveness.max.retries", maxRetries);
        }
        if (scenario != null) {
            config.put("mosip.registration.face.liveness.stub.force", scenario);
        }
        Intent intent = LivenessContract.createIntent(app,
                new LivenessContract.Request(WORKFLOW, FACE, 1, config));
        return Robolectric.buildActivity(LivenessActivity.class, intent).setup();
    }

    /** Feeds frames until the store holds the session's result. */
    private void runUntilResult() {
        for (int i = 0; i < MAX_FRAMES && LivenessSessionStore.shared().lastResult(WORKFLOW, FACE) == null; i++) {
            frames(1);
        }
        assertNotNull("session never finished", LivenessSessionStore.shared().lastResult(WORKFLOW, FACE));
    }

    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            binder.analyzer.analyze(image(new byte[16]));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    /** A 2x2 RGBA image; a too-short buffer makes it unreadable. */
    private ImageProxy image(byte[] rgba) {
        ImageProxy.PlaneProxy plane = mock(ImageProxy.PlaneProxy.class);
        when(plane.getBuffer()).thenReturn(ByteBuffer.wrap(rgba));
        when(plane.getRowStride()).thenReturn(8);
        when(plane.getPixelStride()).thenReturn(4);
        ImageInfo info = mock(ImageInfo.class);
        when(info.getTimestamp()).thenReturn(TimeUnit.MILLISECONDS.toNanos(nextFrameMs));
        nextFrameMs += FRAME_INTERVAL_MS;
        ImageProxy image = mock(ImageProxy.class);
        when(image.getWidth()).thenReturn(2);
        when(image.getHeight()).thenReturn(2);
        when(image.getPlanes()).thenReturn(new ImageProxy.PlaneProxy[]{plane});
        when(image.getImageInfo()).thenReturn(info);
        return image;
    }

    /** Answers the system permission dialog that the activity opened with "Allow". */
    private void grantRequestedCamera(Activity activity) {
        ShadowActivity.PermissionsRequest request = Shadows.shadowOf(activity).getLastRequestedPermission();
        assertNotNull("no permission requested", request);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                new int[]{PackageManager.PERMISSION_GRANTED});
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static LivenessResultSummary result(Activity activity) {
        ShadowActivity shadow = Shadows.shadowOf(activity);
        assertTrue("activity did not finish", activity.isFinishing());
        assertEquals(Activity.RESULT_OK, shadow.getResultCode());
        return LivenessContract.parseResult(shadow.getResultIntent());
    }

    private static String text(Activity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static void click(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static final class FakeBinder implements CameraXFrameSource.CameraBinder {
        private ImageAnalysis.Analyzer analyzer;
        private Consumer<Exception> onFailure;
        private RuntimeException failure;

        @Override
        public void bind(ImageAnalysis.Analyzer value, Consumer<Exception> failed) {
            if (failure != null) {
                throw failure;
            }
            analyzer = value;
            onFailure = failed;
        }

        @Override
        public void unbind() {
            analyzer = null;
        }
    }
}
