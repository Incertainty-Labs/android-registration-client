package io.mosip.registration.livenessmanager.ui;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MessageKeysTest {

    /** API.md §7, plus the three button keys the native screen adds (permission rationale and cancel). */
    private static final String[][] ENGLISH = {
            {"liveness.status.checking", "Checking face liveness…"},
            {"liveness.status.position", "Position your face inside the oval"},
            {"liveness.prompt.turn_left", "Please turn your head to the left"},
            {"liveness.prompt.turn_right", "Please turn your head to the right"},
            {"liveness.prompt.look_up", "Please look up"},
            {"liveness.prompt.look_down", "Please look down"},
            {"liveness.prompt.smile", "Please smile"},
            {"liveness.prompt.open_mouth", "Please open your mouth"},
            {"liveness.prompt.close_eyes", "Please close your eyes for a moment"},
            {"liveness.prompt.blink", "Please blink"},
            {"liveness.prompt.neutral", "Look straight at the camera and hold still"},
            {"liveness.feedback.continue", "Please continue"},
            {"liveness.feedback.detected", "Action detected"},
            {"liveness.feedback.hold_still", "Hold still"},
            {"liveness.feedback.success", "Good, face captured successfully"},
            {"liveness.progress.step", "Step {0} of {1}"},
            {"liveness.guidance.face_not_found", "Keep your face within the capture area"},
            {"liveness.guidance.one_face_only", "Only one person should be in front of the camera"},
            {"liveness.guidance.improve_lighting", "Improve lighting and make sure your face is clearly visible"},
            {"liveness.guidance.look_at_camera", "Look directly at the camera"},
            {"liveness.guidance.follow_prompt", "Follow the prompted action"},
            {"liveness.fail.try_again", "We could not verify face liveness. Please try again."},
            {"liveness.fail.timeout", "Time ran out. Please follow the prompted action and try again."},
            {"liveness.fail.generic", "Face verification could not be completed. Please try again."},
            {"liveness.fail.max_retries", "Face verification could not be completed. Please contact your supervisor."},
            {"liveness.retry.available", "Attempt {0} of {1}. Select Retry to try again."},
            {"liveness.retry.button", "Retry"},
            {"liveness.offline.chip", "Offline"},
            {"liveness.error.device_unavailable", "Face capture device not found. Check the connection."},
            {"liveness.error.device_connection", "Lost connection to the face capture device."},
            {"liveness.error.camera_unavailable", "Camera is not available."},
            {"liveness.error.invalid_frame", "The camera sent unreadable images. Please try again."},
            {"liveness.error.engine", "Face verification is unavailable. Please contact support."},
            {"liveness.status.device_search", "Looking for the face camera…"},
            {"liveness.status.no_action", "Look at the camera. No action needed."},
            {"liveness.badge.verified", "Liveness: verified"},
            {"liveness.badge.not_verified", "Liveness: not verified"},
            {"liveness.button.use_face", "Use face"},
            {"liveness.button.rescan", "Rescan devices"},
            {"liveness.button.open_settings", "Open settings"},
            {"liveness.button.request_override", "Request supervisor override"},
            {"liveness.button.mark_exception", "Mark face exception"},
            {"liveness.button.close", "Close"},
            {"liveness.title.operator_auth", "Operator authentication"},
            {"liveness.title.supervisor_exception", "Supervisor authentication required: biometric exceptions"},
            {"liveness.title.supervisor_eod", "Supervisor authentication: approve packets"},
            {"liveness.permission.title", "Camera access needed"},
            {"liveness.permission.body", "The camera is used only to check that a real person is present. No video is saved."},
            {"liveness.offline.announce", "Working offline"},
            {"liveness.permission.allow", "Allow"},
            {"liveness.permission.not_now", "Not now"},
            {"liveness.button.cancel", "Cancel"}
    };

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void tcUx_everyMessageKey_hasItsEnglishText() {
        for (String[] row : ENGLISH) {
            assertEquals(row[0], row[1], MessageKeys.text(context, row[0]));
        }
    }

    @Test
    public void tcUx_arguments_fillThePlaceholders() {
        assertEquals("Step 2 of 3", MessageKeys.text(context, "liveness.progress.step", 2, 3));
    }

    @Test
    public void tcUx_unknownKey_showsTheKeyInsteadOfFailing() {
        assertEquals("liveness.prompt.unknown", MessageKeys.text(context, "liveness.prompt.unknown"));
    }

    @Test
    public void tcUx_resourceName_replacesDotsWithUnderscores() {
        assertEquals("liveness_prompt_turn_left", MessageKeys.resourceName("liveness.prompt.turn_left"));
    }
}
