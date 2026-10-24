import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';

enum LivenessStatus {
  /// No check yet, or reset.
  idle,

  /// The native liveness screen is open.
  checking,

  /// The check ended; see [LivenessProvider.resultFor].
  done,

  /// The check could not start; see [LivenessProvider.errorFor].
  unavailable,
}

/// Face liveness for the UI (API.md §10): starts the native check and keeps each field's last result, so a pass
/// for one face field (the applicant) never reads as a pass for another (an introducer or guardian).
class LivenessProvider with ChangeNotifier {
  LivenessProvider({LivenessService? service}) : _service = service ?? LivenessService();

  final LivenessService _service;
  final Map<String, LivenessResultDto> _results = {};
  final Map<String, String> _errors = {};

  /// Whether a check is open, or how the last one ended. One check runs at a time.
  LivenessStatus status = LivenessStatus.idle;

  bool _disposed = false;

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  /// A native reply can arrive after the provider is gone (e.g. a reset sent as a screen closes).
  @override
  void notifyListeners() {
    if (!_disposed) {
      super.notifyListeners();
    }
  }

  static String _key(String workflow, String fieldId) => '$workflow/$fieldId';

  /// The field's last result, or null.
  LivenessResultDto? resultFor(String workflow, String fieldId) => _results[_key(workflow, fieldId)];

  /// The PlatformException code of the field's last check that could not start, e.g. LIVENESS_DISABLED, or null.
  String? errorFor(String workflow, String fieldId) => _errors[_key(workflow, fieldId)];

  /// Whether the field's last check passed; only then may its SBI capture follow.
  bool passedFor(String workflow, String fieldId) {
    final result = resultFor(workflow, fieldId);
    return result?.finalState == 'PASSED' && result?.decision == 'LIVE';
  }

  /// UI hints for [workflow] (enabled, mode, maxAttempts, onMaxRetries, activeEnabled, testScreen); empty when
  /// the config cannot be read.
  Future<Map<String?, String?>> hints(String workflow) async {
    try {
      return await _service.getLivenessConfig(workflow);
    } on PlatformException {
      return {};
    }
  }

  /// Whether this build has the B-21 liveness test screen.
  Future<bool> testScreenAllowed() async => (await hints('RESIDENT_REGISTRATION'))['testScreen'] == 'true';

  /// Test screen only: turns liveness on and plays the stub [scenario] (e.g. "PAD") in the next checks; null for
  /// the build's default scenario. The screen exists to exercise liveness, even where the server leaves it off.
  /// Returns null, or the PlatformException code when the build refuses it.
  Future<String?> useTestScenario(String? scenario) async {
    try {
      await _service.setTestOverrides(scenario == null ? 'enabled=true' : 'enabled=true;stub.force=$scenario');
      return null;
    } on PlatformException catch (e) {
      return e.code;
    }
  }

  /// Test screen only: removes the screen's overrides, so later real checks run with the build's config.
  Future<void> clearTestOverrides() async {
    try {
      await _service.setTestOverrides('');
    } on PlatformException {
      // A build without the test screen has none to clear
    }
  }

  /// Runs one check. Returns its result, or null when it could not start (see [errorFor]) or a check is already
  /// open.
  Future<LivenessResultDto?> start(String workflow, String fieldId) async {
    if (status == LivenessStatus.checking) {
      return null;
    }
    final key = _key(workflow, fieldId);
    status = LivenessStatus.checking;
    _errors.remove(key);
    notifyListeners();
    try {
      final result = await _service.startLiveness(workflow, fieldId);
      _results[key] = result;
      status = LivenessStatus.done;
      return result;
    } on PlatformException catch (e) {
      _results.remove(key);
      _errors[key] = e.code;
      status = LivenessStatus.unavailable;
      return null;
    } finally {
      notifyListeners();
    }
  }

  /// Starts the field's attempts again, e.g. for a new registration. Refused (false) while a check is open, since
  /// that check still needs its attempt.
  Future<bool> resetAttempts(String workflow, String fieldId) async {
    if (status == LivenessStatus.checking) {
      return false;
    }
    try {
      await _service.resetAttempts(workflow, fieldId);
    } on PlatformException {
      return false;
    }
    final key = _key(workflow, fieldId);
    _results.remove(key);
    _errors.remove(key);
    status = LivenessStatus.idle;
    notifyListeners();
    return true;
  }
}
