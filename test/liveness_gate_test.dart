import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/utils/liveness_gate.dart';

import 'liveness_provider_test.dart' show FakeLivenessService;

void main() {
  const face = 'individualBiometrics';

  late FakeLivenessService service;
  late LivenessGate gate;
  late int sbiCalls;

  Future<void> sbi() async => sbiCalls++;

  setUp(() {
    service = FakeLivenessService();
    gate = LivenessGate(LivenessProvider(service: service));
    sbiCalls = 0;
  });

  /// Runs the gate for the face while the liveness check ends with [outcome] (a result or an error).
  Future<bool> faceCapture(Object outcome) async {
    final captured = gate.capture(face, 'Face', sbi);
    await Future<void>.delayed(Duration.zero);
    if (outcome is LivenessResultDto) {
      service.pending!.complete(outcome);
    } else {
      service.pending!.completeError(outcome);
    }
    return captured;
  }

  test('TC-AND-05 a live face goes on to the SBI capture', () async {
    expect(await faceCapture(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE')), isTrue);
    expect(sbiCalls, 1);
    expect(service.starts, 1);
  });

  test('TC-AND-06 a failed check never reaches the SBI', () async {
    expect(await faceCapture(LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE')), isFalse);
    expect(sbiCalls, 0);
  });

  test('TC-AND-06 a cancelled check never reaches the SBI', () async {
    expect(await faceCapture(LivenessResultDto(finalState: 'CANCELLED', decision: 'INCONCLUSIVE')), isFalse);
    expect(sbiCalls, 0);
  });

  test('TC-AND-07 used-up attempts never reach the SBI', () async {
    final maxRetries = LivenessResultDto(
        finalState: 'FAILED', decision: 'INCONCLUSIVE', errorCode: 'LIV-011', retryAllowed: false);

    expect(await faceCapture(maxRetries), isFalse);
    expect(sbiCalls, 0);
  });

  test('TC-AND-05 liveness off goes straight to the SBI, as before', () async {
    expect(await faceCapture(PlatformException(code: 'LIVENESS_DISABLED')), isTrue);
    expect(sbiCalls, 1);
  });

  test('TC-AND-06 a check that cannot start blocks the capture', () async {
    expect(await faceCapture(PlatformException(code: 'LIVENESS_START_FAILED')), isFalse);
    expect(sbiCalls, 0);
  });

  test('TC-AND-05 other modalities skip liveness', () async {
    for (final modality in ['Iris', 'LeftHand', 'RightHand', 'Thumbs']) {
      expect(await gate.capture(face, modality, sbi), isTrue);
    }
    expect(sbiCalls, 4);
    expect(service.starts, 0);
  });

  test('TC-AND-05 the exception photo is checked under its own passive workflow', () async {
    final captured = gate.capture(face, 'Exception', sbi);
    await Future<void>.delayed(Duration.zero);
    service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));

    expect(await captured, isTrue);
    expect(service.lastWorkflow, 'RESIDENT_EXCEPTION_PHOTO');
    expect(sbiCalls, 1);
  });

  test('TC-AND-06 a failed exception-photo check never reaches the SBI', () async {
    final captured = gate.capture(face, 'Exception', sbi);
    await Future<void>.delayed(Duration.zero);
    service.pending!.complete(LivenessResultDto(finalState: 'FAILED', decision: 'ATTACK_SUSPECTED'));

    expect(await captured, isFalse);
    expect(sbiCalls, 0);
  });

  test('TC-AND-09 onboarding checks the operator under its own workflow', () async {
    final provider = LivenessProvider(service: service);
    final onboarding = LivenessGate(provider, workflow: 'OPERATOR_ONBOARDING');

    final captured = onboarding.capture('operatorBiometrics', 'Face', sbi);
    await Future<void>.delayed(Duration.zero);
    service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));

    expect(await captured, isTrue);
    expect(service.lastWorkflow, 'OPERATOR_ONBOARDING');
    expect(provider.passedFor('OPERATOR_ONBOARDING', 'operatorBiometrics'), isTrue);
    expect(provider.passedFor('RESIDENT_REGISTRATION', 'operatorBiometrics'), isFalse);
  });

  test('TC-AND-05 an SBI error still reaches the caller', () async {
    Future<void> failingSbi() async => throw PlatformException(code: 'REG-SBI-104');
    final captured = gate.capture(face, 'Face', failingSbi);
    await Future<void>.delayed(Duration.zero);
    service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));

    expect(captured, throwsA(isA<PlatformException>()));
  });

  test('TC-AND-05 each modality names the workflow its card and lock follow', () {
    expect(gate.workflowFor('Face'), LivenessGate.residentWorkflow);
    expect(gate.workflowFor('Exception'), LivenessGate.exceptionPhotoWorkflow);
    expect(gate.workflowFor('Iris'), isNull);
    expect(LivenessGate(LivenessProvider(service: service), workflow: LivenessGate.onboardingWorkflow)
        .workflowFor('Face'), LivenessGate.onboardingWorkflow);
  });
}
