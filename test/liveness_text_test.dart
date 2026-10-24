import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/utils/liveness_text.dart';

void main() {
  final l10n = lookupAppLocalizations(const Locale('en'));

  test('TC-AND-06 every API.md §7 message key has its English text', () {
    const english = [
      ['liveness.status.checking', 'Checking face liveness…'],
      ['liveness.status.position', 'Position your face inside the oval'],
      ['liveness.prompt.turn_left', 'Please turn your head to the left'],
      ['liveness.prompt.turn_right', 'Please turn your head to the right'],
      ['liveness.prompt.look_up', 'Please look up'],
      ['liveness.prompt.look_down', 'Please look down'],
      ['liveness.prompt.smile', 'Please smile'],
      ['liveness.prompt.open_mouth', 'Please open your mouth'],
      ['liveness.prompt.close_eyes', 'Please close your eyes for a moment'],
      ['liveness.prompt.blink', 'Please blink'],
      ['liveness.prompt.neutral', 'Look straight at the camera and hold still'],
      ['liveness.feedback.continue', 'Please continue'],
      ['liveness.feedback.detected', 'Action detected'],
      ['liveness.feedback.hold_still', 'Hold still'],
      ['liveness.feedback.success', 'Good, face captured successfully'],
      ['liveness.progress.step', 'Step 1 of 3'],
      ['liveness.guidance.face_not_found', 'Keep your face within the capture area'],
      ['liveness.guidance.one_face_only', 'Only one person should be in front of the camera'],
      ['liveness.guidance.improve_lighting', 'Improve lighting and make sure your face is clearly visible'],
      ['liveness.guidance.look_at_camera', 'Look directly at the camera'],
      ['liveness.guidance.follow_prompt', 'Follow the prompted action'],
      ['liveness.fail.try_again', 'We could not verify face liveness. Please try again.'],
      ['liveness.fail.timeout', 'Time ran out. Please follow the prompted action and try again.'],
      ['liveness.fail.generic', 'Face verification could not be completed. Please try again.'],
      ['liveness.fail.max_retries', 'Face verification could not be completed. Please contact your supervisor.'],
      ['liveness.retry.available', 'Attempt 1 of 3. Select Retry to try again.'],
      ['liveness.retry.button', 'Retry'],
      ['liveness.offline.chip', 'Offline'],
      ['liveness.error.device_unavailable', 'Face capture device not found. Check the connection.'],
      ['liveness.error.device_connection', 'Lost connection to the face capture device.'],
      ['liveness.error.camera_unavailable', 'Camera is not available.'],
      ['liveness.error.invalid_frame', 'The camera sent unreadable images. Please try again.'],
      ['liveness.error.engine', 'Face verification is unavailable. Please contact support.'],
      ['liveness.status.device_search', 'Looking for the face camera…'],
      ['liveness.status.no_action', 'Look at the camera. No action needed.'],
      ['liveness.badge.verified', 'Liveness: verified'],
      ['liveness.badge.not_verified', 'Liveness: not verified'],
      ['liveness.button.use_face', 'Use face'],
      ['liveness.button.rescan', 'Rescan devices'],
      ['liveness.button.open_settings', 'Open settings'],
      ['liveness.button.request_override', 'Request supervisor override'],
      ['liveness.button.mark_exception', 'Mark face exception'],
      ['liveness.button.close', 'Close'],
      ['liveness.title.operator_auth', 'Operator authentication'],
      ['liveness.title.supervisor_exception', 'Supervisor authentication required: biometric exceptions'],
      ['liveness.title.supervisor_eod', 'Supervisor authentication: approve packets'],
      ['liveness.permission.title', 'Camera access needed'],
      ['liveness.permission.body', 'The camera is used only to check that a real person is present. No video is saved.'],
      ['liveness.offline.announce', 'Working offline'],
    ];
    for (final row in english) {
      expect(livenessText(l10n, row[0], const [1, 3]), row[1], reason: row[0]);
    }
  });

  test('TC-AND-06 an unknown key shows the key instead of failing', () {
    expect(livenessText(l10n, 'liveness.prompt.unknown'), 'liveness.prompt.unknown');
  });
}
