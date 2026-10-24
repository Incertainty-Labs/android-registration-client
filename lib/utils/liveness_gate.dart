import 'package:registration_client/provider/liveness_provider.dart';

/// Puts the face liveness check in front of a resident face capture (B-06, UI_UX_SPEC §7.1): the SBI capture
/// runs only after the face passed, or when liveness is off for the workflow.
class LivenessGate {
  LivenessGate(this._provider);

  /// Every registration process (new, update, lost) is the resident registration workflow for liveness.
  static const workflow = 'RESIDENT_REGISTRATION';

  /// The modality name the capture widgets use for the face.
  static const faceModality = 'Face';

  final LivenessProvider _provider;

  /// Captures [modality] for [fieldId] through [sbiCapture], after the liveness check for a face.
  ///
  /// Returns false, without calling [sbiCapture], when the face did not pass (failed, cancelled, attempts used
  /// up) or the check could not start; the result card shows why. Errors from [sbiCapture] reach the caller.
  /// Other modalities, and the exception photo, go straight to [sbiCapture]: the API has no per-call passive-only
  /// check yet.
  Future<bool> capture(String fieldId, String modality, Future<void> Function() sbiCapture) async {
    if (modality != faceModality) {
      await sbiCapture();
      return true;
    }
    final result = await _provider.start(workflow, fieldId);
    final liveFace = result != null && _provider.passedFor(workflow, fieldId);
    final livenessOff = result == null && _provider.errorFor(workflow, fieldId) == 'LIVENESS_DISABLED';
    if (!liveFace && !livenessOff) {
      return false;
    }
    await sbiCapture();
    return true;
  }
}
