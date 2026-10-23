import 'package:pigeon/pigeon.dart';

/// What the UI may know about one liveness attempt (API.md §10). Packet evidence never travels here.
class LivenessResultDto {
  String? decision; // LIVE | ATTACK_SUSPECTED | INCONCLUSIVE
  String? finalState; // PASSED | FAILED | ERROR | CANCELLED
  String? errorCode; // LIV-0xx or null
  String? messageKey; // key from API.md §7 to show
  double? passiveScore; // null unless diagnostics.enabled=true; never shown in normal UI
  List<String?>? challenges; // "TURN_LEFT:PASSED"
  int? attempt;
  int? maxAttempts;
  bool? retryAllowed;
}

class AuthResultDto {
  bool? authenticated;
  String? errorCode; // LIV-0xx, or REG-AUTH-xxx for match failure
  String? messageKey;
  LivenessResultDto? liveness;
}

@HostApi()
abstract class LivenessApi {
  /// Opens LivenessActivity (request code 6) and returns when it closes.
  /// Always completes: success, cancel (finalState CANCELLED) or error.
  @async
  LivenessResultDto startLiveness(String workflow, String fieldId);

  /// Resolved policy for a workflow, for UI hints (e.g. retries left).
  @async
  Map<String?, String?> getLivenessConfig(String workflow);

  /// Clears the attempt counter for a field (e.g. new registration).
  @async
  void resetAttempts(String workflow, String fieldId);

  /// Liveness → SBI capture → passive PAD + binder → offline 1:1 FACE match.
  @async
  AuthResultDto authenticateWithFace(String userId, String workflow);
}
