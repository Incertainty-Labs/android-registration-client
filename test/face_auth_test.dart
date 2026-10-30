import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/ui/widgets/face_auth_button.dart';
import 'package:registration_client/utils/face_auth.dart';

import 'liveness_provider_test.dart' show FakeLivenessService;

void main() {
  const workflow = 'OPERATOR_PACKET_AUTH';
  const operator = '110011';

  late FakeLivenessService service;
  late LivenessProvider provider;
  late int sbiCalls;

  Future<void> sbi() async => sbiCalls++;

  setUp(() {
    service = FakeLivenessService()
      ..config = {'enabled': 'true', 'faceAuthAvailable': 'true', 'onMaxRetries': 'BLOCK'};
    provider = LivenessProvider(service: service);
    sbiCalls = 0;
  });

  /// Runs the flow while the liveness check ends with [liveness].
  Future<FaceAuthOutcome> run(LivenessResultDto liveness) async {
    final outcome = FaceAuth(provider, workflow: workflow, sbiCapture: sbi).run(operator);
    await Future<void>.delayed(Duration.zero);
    service.pending!.complete(liveness);
    return outcome;
  }

  group('FaceAuth', () {
    test('TC-AND-10 a live face that matches authenticates the operator', () async {
      final outcome = await run(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));

      expect(outcome.authenticated, isTrue);
      expect(sbiCalls, 1);
      expect(service.lastWorkflow, workflow);
      expect(service.authCalls, ['$workflow/$operator']);
    });

    test('TC-AND-10 a failed liveness check never reaches the capture or the match', () async {
      final outcome = await run(LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE'));

      expect(outcome.authenticated, isFalse);
      expect(sbiCalls, 0);
      expect(service.authCalls, isEmpty);
    });

    test('TC-AND-11 another face is not authenticated and says why', () async {
      service.authResult = AuthResultDto(
          authenticated: false, errorCode: 'FACE_NOT_MATCHED', messageKey: 'liveness.auth.no_match');

      final outcome = await run(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));

      expect(outcome.authenticated, isFalse);
      expect(outcome.messageKey, 'liveness.auth.no_match');
    });

    test('TC-AND-10 an SBI error reaches the caller', () async {
      Future<void> failingSbi() async => throw PlatformException(code: 'REG-SBI-104');
      final outcome = FaceAuth(provider, workflow: workflow, sbiCapture: failingSbi).run(operator);
      await Future<void>.delayed(Duration.zero);
      service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));

      expect(outcome, throwsA(isA<PlatformException>()));
    });
  });

  group('FaceAuthButton', () {
    Future<void> pumpButton(WidgetTester tester, VoidCallback onAuthenticated) async {
      await tester.pumpWidget(ChangeNotifierProvider.value(
        value: provider,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
          home: Scaffold(
            body: FaceAuthButton(
                userId: operator, workflow: workflow, onAuthenticated: onAuthenticated, sbiCapture: sbi),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('TC-AND-10 is offered only when face auth is available', (tester) async {
      service.config = {'enabled': 'true', 'faceAuthAvailable': 'false'};
      await pumpButton(tester, () {});

      expect(find.text('Use face'), findsNothing);
    });

    testWidgets('TC-AND-10 is not offered when liveness is off', (tester) async {
      service.config = {'enabled': 'false', 'faceAuthAvailable': 'true'};
      await pumpButton(tester, () {});

      expect(find.text('Use face'), findsNothing);
    });

    testWidgets('TC-AND-10 each auth dialog starts with fresh attempts, whoever used them up before', (tester) async {
      final earlier = provider.start(workflow, FaceAuth.fieldId);
      service.pending!.complete(LivenessResultDto(
          finalState: 'FAILED', decision: 'INCONCLUSIVE', errorCode: 'LIV-011', retryAllowed: false));
      await earlier;
      expect(provider.blockedFor(workflow, FaceAuth.fieldId), isTrue);

      await pumpButton(tester, () {});

      expect(service.resets, contains('$workflow/${FaceAuth.fieldId}'));
      expect(provider.blockedFor(workflow, FaceAuth.fieldId), isFalse);
      expect(find.text('Use face'), findsOneWidget);
    });

    testWidgets('TC-AND-10 a match authenticates', (tester) async {
      var authenticated = 0;
      await pumpButton(tester, () => authenticated++);

      await tester.tap(find.text('Use face'));
      await tester.pump();
      service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));
      await tester.pumpAndSettle();

      expect(authenticated, 1);
    });

    testWidgets('TC-AND-11 no match shows the reason and does not authenticate', (tester) async {
      var authenticated = 0;
      service.authResult = AuthResultDto(
          authenticated: false, errorCode: 'FACE_NOT_MATCHED', messageKey: 'liveness.auth.no_match');
      await pumpButton(tester, () => authenticated++);

      await tester.tap(find.text('Use face'));
      await tester.pump();
      service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));
      await tester.pumpAndSettle();

      expect(authenticated, 0);
      expect(find.text('Face does not match. Try again or use your password.'), findsOneWidget);
    });
  });
}
