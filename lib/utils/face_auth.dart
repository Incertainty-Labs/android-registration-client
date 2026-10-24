import 'package:registration_client/pigeon/biometrics_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/utils/liveness_gate.dart';

/// How a face authentication ended.
class FaceAuthOutcome {
  const FaceAuthOutcome(this.authenticated, [this.messageKey]);

  final bool authenticated;

  /// Why it failed (an API.md §7 key), or null when the liveness result card already says why.
  final String? messageKey;
}

/// Operator and supervisor face authentication (B-09, UI_UX_SPEC §6.4): liveness, then the SBI face capture under
/// the field operatorAuth (held natively, never in the packet), then the 1:1 match against the user's templates.
class FaceAuth {
  FaceAuth(this._provider, {required this.workflow, Future<void> Function()? sbiCapture})
      : _sbiCapture = sbiCapture ?? captureOperatorFace;

  /// The field the authenticating face is captured under.
  static const fieldId = 'operatorAuth';

  /// The liveness workflow, e.g. OPERATOR_PACKET_AUTH or SUPERVISOR_EOD_AUTH.
  final String workflow;

  final LivenessProvider _provider;
  final Future<void> Function() _sbiCapture;

  /// The SBI face capture under [fieldId], held natively for one match (or one seed).
  static Future<void> captureOperatorFace() =>
      BiometricsApi().invokeDiscoverSbi(fieldId, LivenessGate.faceModality);

  /// Authenticates [userId]. SBI errors reach the caller.
  Future<FaceAuthOutcome> run(String userId) async {
    final captured = await LivenessGate(_provider, workflow: workflow)
        .capture(fieldId, LivenessGate.faceModality, _sbiCapture);
    if (!captured) {
      return const FaceAuthOutcome(false);
    }
    final result = await _provider.authenticateWithFace(userId, workflow);
    if (result == null) {
      return const FaceAuthOutcome(false, 'liveness.auth.unavailable');
    }
    return result.authenticated == true ? const FaceAuthOutcome(true) : FaceAuthOutcome(false, result.messageKey);
  }
}
