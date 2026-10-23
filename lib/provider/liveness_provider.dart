import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';

enum LivenessStatus {
  /// No check yet, or reset.
  idle,

  /// The native liveness screen is open.
  checking,

  /// The check ended; see [LivenessProvider.lastResult].
  done,

  /// The check could not start; see [LivenessProvider.errorCode].
  unavailable,
}

/// Face liveness for the UI (API.md §10): starts the native check and keeps its last result.
class LivenessProvider with ChangeNotifier {
  LivenessProvider({LivenessService? service}) : _service = service ?? LivenessService();

  final LivenessService _service;

  LivenessStatus status = LivenessStatus.idle;
  LivenessResultDto? lastResult;
  String? errorCode;

  /// Whether the last check passed; only then may the SBI capture follow.
  bool get passed => lastResult?.finalState == 'PASSED' && lastResult?.decision == 'LIVE';

  /// Whether liveness is on for [workflow]; false when the config cannot be read.
  Future<bool> isEnabled(String workflow) async {
    try {
      final config = await _service.getLivenessConfig(workflow);
      return config['enabled'] == 'true';
    } on PlatformException {
      return false;
    }
  }

  /// Runs one check. Returns its result, or null when it could not start or a check is already open.
  Future<LivenessResultDto?> start(String workflow, String fieldId) async {
    if (status == LivenessStatus.checking) {
      return null;
    }
    status = LivenessStatus.checking;
    errorCode = null;
    notifyListeners();
    try {
      lastResult = await _service.startLiveness(workflow, fieldId);
      status = LivenessStatus.done;
      return lastResult;
    } on PlatformException catch (e) {
      lastResult = null;
      errorCode = e.code;
      status = LivenessStatus.unavailable;
      return null;
    } finally {
      notifyListeners();
    }
  }

  /// Starts the field's attempts again, e.g. for a new registration.
  Future<void> resetAttempts(String workflow, String fieldId) async {
    await _service.resetAttempts(workflow, fieldId);
    lastResult = null;
    errorCode = null;
    status = LivenessStatus.idle;
    notifyListeners();
  }
}
