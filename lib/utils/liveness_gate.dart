import 'package:flutter/services.dart';
import 'package:registration_client/provider/liveness_provider.dart';

/// Puts the face liveness check in front of a face capture (B-06 residents, B-19 operator onboarding;
/// UI_UX_SPEC §7.1): the SBI capture runs only after the face passed, or when liveness is off for the workflow.
class LivenessGate {
  LivenessGate(this._provider, {this.workflow = residentWorkflow});

  /// Every registration process (new, update, lost) is the resident registration workflow for liveness.
  static const residentWorkflow = 'RESIDENT_REGISTRATION';

  /// Operator onboarding (policy `onboard_auth`): attempts and policy kept apart from residents'.
  static const onboardingWorkflow = 'OPERATOR_ONBOARDING';

  /// The resident's exception photo (policy `exception_photo`): a passive check, no challenge.
  static const exceptionPhotoWorkflow = 'RESIDENT_EXCEPTION_PHOTO';

  /// The liveness workflow this gate checks under.
  final String workflow;

  /// The modality name the capture widgets use for the face.
  static const faceModality = 'Face';

  /// The modality name the capture widgets use for the exception photo.
  static const exceptionModality = 'Exception';

  final LivenessProvider _provider;

  /// The liveness workflow [modality] is checked under, or null when it has no liveness check.
  String? workflowFor(String modality) {
    if (modality == faceModality) {
      return workflow;
    }
    return modality == exceptionModality ? exceptionPhotoWorkflow : null;
  }

  /// Captures [modality] for [fieldId] through [sbiCapture], after the liveness check for a face or the
  /// exception photo (B-07; the photo under [exceptionPhotoWorkflow]).
  ///
  /// Returns false, without calling [sbiCapture], when the face did not pass (failed, cancelled, attempts used
  /// up) or the check could not start; the result card shows why. Errors from [sbiCapture] reach the caller.
  /// Other modalities go straight to [sbiCapture].
  Future<bool> capture(String fieldId, String modality, Future<void> Function() sbiCapture) async {
    final checked = workflowFor(modality);
    if (checked == null) {
      await sbiCapture();
      return true;
    }
    final result = await _provider.start(checked, fieldId);
    final liveFace = result != null && _provider.passedFor(checked, fieldId);
    final livenessOff = result == null && _provider.errorFor(checked, fieldId) == 'LIVENESS_DISABLED';
    if (!liveFace && !livenessOff) {
      return false;
    }
    try {
      await sbiCapture();
    } on PlatformException catch (e) {
      if (rejectedPassCodes.contains(e.code)) {
        _provider.forgetPass(checked, fieldId);
      }
      rethrow;
    }
    return true;
  }

  /// SBI errors with which the native side refuses the capture a pass let through: the capture was not fresh
  /// (REG-LIV-014), or no fresh, unused pass covered it (REG-SBI-121).
  static const rejectedPassCodes = {'REG-LIV-014', 'REG-SBI-121'};
}
