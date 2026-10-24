import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';
import 'package:registration_client/provider/liveness_provider.dart';

class FakeLivenessService implements LivenessService {
  Map<String?, String?> config = {'enabled': 'true', 'maxAttempts': '3'};
  Object? configError;
  int configCalls = 0;

  /// When set, getLivenessConfig waits; each call adds its workflow and completer here, in order.
  List<MapEntry<String, Completer<Map<String?, String?>>>>? heldConfigs;
  Completer<LivenessResultDto>? pending;
  int starts = 0;
  String? lastWorkflow;
  final List<String> resets = [];
  Object? resetError;
  String? testOverrides;
  Object? testOverridesError;

  @override
  Future<Map<String?, String?>> getLivenessConfig(String workflow) async {
    configCalls++;
    if (configError != null) throw configError!;
    if (heldConfigs != null) {
      final held = Completer<Map<String?, String?>>();
      heldConfigs!.add(MapEntry(workflow, held));
      return held.future;
    }
    return config;
  }

  @override
  Future<LivenessResultDto> startLiveness(String workflow, String fieldId) {
    starts++;
    lastWorkflow = workflow;
    pending = Completer<LivenessResultDto>();
    return pending!.future;
  }

  @override
  Future<void> resetAttempts(String workflow, String fieldId) async {
    if (resetError != null) throw resetError!;
    resets.add('$workflow/$fieldId');
  }

  @override
  Future<void> setTestOverrides(String overrides) async {
    if (testOverridesError != null) throw testOverridesError!;
    testOverrides = overrides;
  }
}

LivenessResultDto result(String finalState, String decision) =>
    LivenessResultDto(finalState: finalState, decision: decision, attempt: 1, maxAttempts: 3, retryAllowed: true);

void main() {
  const workflow = 'RESIDENT_REGISTRATION';
  const face = 'individualBiometrics';
  const introducer = 'introducerBiometrics';

  late FakeLivenessService service;
  late LivenessProvider provider;

  setUp(() {
    service = FakeLivenessService();
    provider = LivenessProvider(service: service);
  });

  Future<void> run(String fieldId, LivenessResultDto dto) async {
    final future = provider.start(workflow, fieldId);
    service.pending!.complete(dto);
    await future;
  }

  test('TC-AND-02 start shows checking, then keeps the result for its field', () async {
    final future = provider.start(workflow, face);
    expect(provider.status, LivenessStatus.checking);

    service.pending!.complete(result('PASSED', 'LIVE'));
    final dto = await future;

    expect(dto!.finalState, 'PASSED');
    expect(provider.status, LivenessStatus.done);
    expect(provider.resultFor(workflow, face)!.finalState, 'PASSED');
    expect(provider.passedFor(workflow, face), isTrue);
  });

  test('TC-AND-02 a pass for one face field is not a pass for another', () async {
    await run(face, result('PASSED', 'LIVE'));

    expect(provider.passedFor(workflow, introducer), isFalse);
    expect(provider.resultFor(workflow, introducer), isNull);
  });

  test('TC-AND-02 a failed attempt is not a pass', () async {
    await run(face, result('FAILED', 'INCONCLUSIVE'));

    expect(provider.passedFor(workflow, face), isFalse);
  });

  test('TC-AND-02 a platform error is kept as the field\'s code', () async {
    final future = provider.start(workflow, face);
    service.pending!.completeError(PlatformException(code: 'LIVENESS_DISABLED'));

    expect(await future, isNull);
    expect(provider.status, LivenessStatus.unavailable);
    expect(provider.errorFor(workflow, face), 'LIVENESS_DISABLED');
    expect(provider.resultFor(workflow, face), isNull);
  });

  test('TC-AND-02 a new check clears the field\'s earlier error', () async {
    final failed = provider.start(workflow, face);
    service.pending!.completeError(PlatformException(code: 'LIVENESS_START_FAILED'));
    await failed;

    await run(face, result('PASSED', 'LIVE'));

    expect(provider.errorFor(workflow, face), isNull);
  });

  test('TC-AND-02 a second start while checking is ignored', () async {
    final first = provider.start(workflow, face);

    expect(await provider.start(workflow, face), isNull);
    expect(service.starts, 1);

    service.pending!.complete(result('PASSED', 'LIVE'));
    expect((await first)!.finalState, 'PASSED');
  });

  test('TC-AND-02 notifies listeners on every change', () async {
    var notifications = 0;
    provider.addListener(() => notifications++);

    await run(face, result('PASSED', 'LIVE'));

    expect(notifications, 2);
  });

  test('TC-AND-02 resetAttempts clears only that field', () async {
    await run(face, result('FAILED', 'INCONCLUSIVE'));
    await run(introducer, result('PASSED', 'LIVE'));

    expect(await provider.resetAttempts(workflow, face), isTrue);

    expect(service.resets, ['$workflow/$face']);
    expect(provider.resultFor(workflow, face), isNull);
    expect(provider.passedFor(workflow, introducer), isTrue);
  });

  test('TC-AND-02 resetAttempts is refused while a check is open', () async {
    final open = provider.start(workflow, face);

    expect(await provider.resetAttempts(workflow, face), isFalse);
    expect(service.resets, isEmpty);

    service.pending!.complete(result('PASSED', 'LIVE'));
    await open;
  });

  test('B-21 the test screen is allowed only when the build says so', () async {
    expect(await provider.testScreenAllowed(), isFalse);

    service.config = {'enabled': 'true', 'testScreen': 'true'};
    expect(await provider.testScreenAllowed(), isTrue);
  });

  test('B-21 the test screen turns liveness on and plays the chosen scenario', () async {
    expect(await provider.useTestScenario('PAD'), isNull);
    expect(service.testOverrides, 'enabled=true;stub.force=PAD');

    await provider.useTestScenario(null);
    expect(service.testOverrides, 'enabled=true');
  });

  test('B-21 a refused scenario is reported, not thrown', () async {
    service.testOverridesError = PlatformException(code: 'TEST_OVERRIDES_NOT_ALLOWED');

    expect(await provider.useTestScenario('PAD'), 'TEST_OVERRIDES_NOT_ALLOWED');
  });

  test('B-21 hints come from the config, empty when it cannot be read', () async {
    expect((await provider.hints('RESIDENT_REGISTRATION'))['maxAttempts'], '3');

    service.configError = PlatformException(code: 'LIVENESS_BAD_REQUEST');
    expect(await provider.hints('RESIDENT_REGISTRATION'), isEmpty);
  });

  test('TC-AND-06 a field is blocked only once its attempts are used up', () async {
    await run(face, LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE', retryAllowed: true));
    expect(provider.blockedFor(workflow, face), isFalse);

    await run(face, LivenessResultDto(
        finalState: 'FAILED', decision: 'INCONCLUSIVE', errorCode: 'LIV-011', retryAllowed: false));
    expect(provider.blockedFor(workflow, face), isTrue);
    expect(provider.blockedFor(workflow, introducer), isFalse);
  });

  test('TC-AND-06 a new registration resets every field the workflow ran', () async {
    await run(face, result('FAILED', 'INCONCLUSIVE'));
    await run(introducer, result('PASSED', 'LIVE'));
    final other = provider.start('OPERATOR_LOGIN', 'operatorAuth');
    service.pending!.complete(result('PASSED', 'LIVE'));
    await other;

    await provider.resetWorkflow(workflow);

    expect(service.resets.toSet(), {'$workflow/$face', '$workflow/$introducer'});
    expect(provider.resultFor(workflow, face), isNull);
    expect(provider.passedFor('OPERATOR_LOGIN', 'operatorAuth'), isTrue);
  });

  test('TC-AND-02 resetAttempts refused natively reports false', () async {
    service.resetError = PlatformException(code: 'LIVENESS_IN_PROGRESS');

    expect(await provider.resetAttempts(workflow, face), isFalse);
  });
}
