import 'package:flutter_gen/gen_l10n/app_localizations.dart';

/// The text of a liveness message key (API.md §7), e.g. `liveness.fail.try_again`. ARB names cannot contain
/// dots, so each key is the ARB entry with its dots as underscores. [args] fill `{0}` and `{1}` (attempt and
/// total, or step and total). An unknown key shows the key itself, so a key the engine adds later never fails.
String livenessText(AppLocalizations l10n, String key, [List<int> args = const []]) {
  int arg(int i) => i < args.length ? args[i] : 0;
  switch (key) {
    case 'liveness.status.checking':
      return l10n.liveness_status_checking;
    case 'liveness.status.position':
      return l10n.liveness_status_position;
    case 'liveness.prompt.turn_left':
      return l10n.liveness_prompt_turn_left;
    case 'liveness.prompt.turn_right':
      return l10n.liveness_prompt_turn_right;
    case 'liveness.prompt.look_up':
      return l10n.liveness_prompt_look_up;
    case 'liveness.prompt.look_down':
      return l10n.liveness_prompt_look_down;
    case 'liveness.prompt.smile':
      return l10n.liveness_prompt_smile;
    case 'liveness.prompt.open_mouth':
      return l10n.liveness_prompt_open_mouth;
    case 'liveness.prompt.close_eyes':
      return l10n.liveness_prompt_close_eyes;
    case 'liveness.prompt.blink':
      return l10n.liveness_prompt_blink;
    case 'liveness.prompt.neutral':
      return l10n.liveness_prompt_neutral;
    case 'liveness.feedback.continue':
      return l10n.liveness_feedback_continue;
    case 'liveness.feedback.detected':
      return l10n.liveness_feedback_detected;
    case 'liveness.feedback.hold_still':
      return l10n.liveness_feedback_hold_still;
    case 'liveness.feedback.success':
      return l10n.liveness_feedback_success;
    case 'liveness.progress.step':
      return l10n.liveness_progress_step(arg(0), arg(1));
    case 'liveness.guidance.face_not_found':
      return l10n.liveness_guidance_face_not_found;
    case 'liveness.guidance.one_face_only':
      return l10n.liveness_guidance_one_face_only;
    case 'liveness.guidance.improve_lighting':
      return l10n.liveness_guidance_improve_lighting;
    case 'liveness.guidance.look_at_camera':
      return l10n.liveness_guidance_look_at_camera;
    case 'liveness.guidance.follow_prompt':
      return l10n.liveness_guidance_follow_prompt;
    case 'liveness.fail.try_again':
      return l10n.liveness_fail_try_again;
    case 'liveness.fail.timeout':
      return l10n.liveness_fail_timeout;
    case 'liveness.fail.generic':
      return l10n.liveness_fail_generic;
    case 'liveness.fail.max_retries':
      return l10n.liveness_fail_max_retries;
    case 'liveness.retry.available':
      return l10n.liveness_retry_available(arg(0), arg(1));
    case 'liveness.retry.button':
      return l10n.liveness_retry_button;
    case 'liveness.offline.chip':
      return l10n.liveness_offline_chip;
    case 'liveness.error.device_unavailable':
      return l10n.liveness_error_device_unavailable;
    case 'liveness.error.device_connection':
      return l10n.liveness_error_device_connection;
    case 'liveness.error.camera_unavailable':
      return l10n.liveness_error_camera_unavailable;
    case 'liveness.error.invalid_frame':
      return l10n.liveness_error_invalid_frame;
    case 'liveness.error.engine':
      return l10n.liveness_error_engine;
    case 'liveness.status.device_search':
      return l10n.liveness_status_device_search;
    case 'liveness.status.no_action':
      return l10n.liveness_status_no_action;
    case 'liveness.badge.verified':
      return l10n.liveness_badge_verified;
    case 'liveness.badge.not_verified':
      return l10n.liveness_badge_not_verified;
    case 'liveness.button.use_face':
      return l10n.liveness_button_use_face;
    case 'liveness.button.rescan':
      return l10n.liveness_button_rescan;
    case 'liveness.button.open_settings':
      return l10n.liveness_button_open_settings;
    case 'liveness.button.request_override':
      return l10n.liveness_button_request_override;
    case 'liveness.button.mark_exception':
      return l10n.liveness_button_mark_exception;
    case 'liveness.button.close':
      return l10n.liveness_button_close;
    case 'liveness.title.operator_auth':
      return l10n.liveness_title_operator_auth;
    case 'liveness.title.supervisor_exception':
      return l10n.liveness_title_supervisor_exception;
    case 'liveness.title.supervisor_eod':
      return l10n.liveness_title_supervisor_eod;
    case 'liveness.permission.title':
      return l10n.liveness_permission_title;
    case 'liveness.permission.body':
      return l10n.liveness_permission_body;
    case 'liveness.offline.announce':
      return l10n.liveness_offline_announce;
    default:
      return key;
  }
}
