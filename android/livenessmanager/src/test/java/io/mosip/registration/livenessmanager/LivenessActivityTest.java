package io.mosip.registration.livenessmanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        LivenessSessionStore.shared().reset(WORKFLOW, FACE);
    }

    @After
    public void tearDown() {
        LivenessActivity.binderFactory = LivenessActivity.CAMERAX_BINDER;
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
    public void tcAnd_requestWithoutWorkflow_finishesWithoutAResult() {
        Intent intent = new Intent(app, LivenessActivity.class);

        LivenessActivity activity = Robolectric.buildActivity(LivenessActivity.class, intent).setup().get();

        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
        assertNull(Shadows.shadowOf(activity).getResultIntent());
    }

    private ActivityController<LivenessActivity> launch(String scenario) {
        Map<String, String> config = new HashMap<>();
        config.put("mosip.registration.face.liveness.enabled", "true");
        config.put("mosip.registration.face.liveness.stub.force", scenario);
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
        private RuntimeException failure;

        @Override
        public void bind(ImageAnalysis.Analyzer value) {
            if (failure != null) {
                throw failure;
            }
            analyzer = value;
        }

        @Override
        public void unbind() {
            analyzer = null;
        }
    }
}
