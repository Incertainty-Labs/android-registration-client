import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';
import 'package:registration_client/provider/liveness_provider.dart';

class FakeLivenessService implements LivenessService {
  Map<String?, String?> config = {'enabled': 'true', 'maxAttempts': '3'};
  Object? configError;
  Completer<LivenessResultDto>? pending;
  int starts = 0;
  final List<String> resets = [];

  @override
  Future<Map<String?, String?>> getLivenessConfig(String workflow) async {
    if (configError != null) throw configError!;
    return config;
  }

  @override
  Future<LivenessResultDto> startLiveness(String workflow, String fieldId) {
    starts++;
    pending = Completer<LivenessResultDto>();
    return pending!.future;
  }

  @override
  Future<void> resetAttempts(String workflow, String fieldId) async {
    resets.add('$workflow/$fieldId');
  }
}

LivenessResultDto result(String finalState, String decision) =>
    LivenessResultDto(finalState: finalState, decision: decision, attempt: 1, maxAttempts: 3, retryAllowed: true);

void main() {
  const workflow = 'RESIDENT_REGISTRATION';
  const face = 'individualBiometrics';

  late FakeLivenessService service;
  late LivenessProvider provider;

  setUp(() {
    service = FakeLivenessService();
    provider = LivenessProvider(service: service);
  });

  test('TC-AND-02 isEnabled follows the config', () async {
    expect(await provider.isEnabled(workflow), isTrue);

    service.config = {'enabled': 'false'};
    expect(await provider.isEnabled(workflow), isFalse);
  });

  test('TC-AND-02 isEnabled is false when the config cannot be read', () async {
    service.configError = PlatformException(code: 'LIVENESS_BAD_REQUEST');

    expect(await provider.isEnabled(workflow), isFalse);
  });

  test('TC-AND-02 start shows checking, then keeps the result', () async {
    final future = provider.start(workflow, face);
    expect(provider.status, LivenessStatus.checking);

    service.pending!.complete(result('PASSED', 'LIVE'));
    final dto = await future;

    expect(dto!.finalState, 'PASSED');
    expect(provider.status, LivenessStatus.done);
    expect(provider.passed, isTrue);
  });

  test('TC-AND-02 a failed attempt is not a pass', () async {
    final future = provider.start(workflow, face);
    service.pending!.complete(result('FAILED', 'INCONCLUSIVE'));
    await future;

    expect(provider.passed, isFalse);
  });

  test('TC-AND-02 a platform error is kept as its code', () async {
    final future = provider.start(workflow, face);
    service.pending!.completeError(PlatformException(code: 'LIVENESS_DISABLED'));

    expect(await future, isNull);
    expect(provider.status, LivenessStatus.unavailable);
    expect(provider.errorCode, 'LIVENESS_DISABLED');
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

    final future = provider.start(workflow, face);
    service.pending!.complete(result('PASSED', 'LIVE'));
    await future;

    expect(notifications, 2);
  });

  test('TC-AND-02 resetAttempts clears the field and the last result', () async {
    final future = provider.start(workflow, face);
    service.pending!.complete(result('FAILED', 'INCONCLUSIVE'));
    await future;

    await provider.resetAttempts(workflow, face);

    expect(service.resets, ['$workflow/$face']);
    expect(provider.lastResult, isNull);
    expect(provider.status, LivenessStatus.idle);
  });
}
