import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_android/liveness_service_impl.dart';

/// Face liveness on the native side (API.md §10). Widgets go through LivenessProvider, never this directly.
abstract class LivenessService {
  /// Opens the liveness screen; always completes, with finalState CANCELLED when the user leaves.
  /// Throws a PlatformException (LIVENESS_DISABLED, LIVENESS_IN_PROGRESS, ...) when it cannot start.
  Future<LivenessResultDto> startLiveness(String workflow, String fieldId);

  /// UI hints for a workflow: enabled, mode, maxAttempts, onMaxRetries, activeEnabled.
  Future<Map<String?, String?>> getLivenessConfig(String workflow);

  /// Clears the attempt counter for a field, e.g. for a new registration.
  Future<void> resetAttempts(String workflow, String fieldId);

  factory LivenessService() => getLivenessServiceImpl();
}
