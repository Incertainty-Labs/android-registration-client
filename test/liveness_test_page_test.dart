import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/ui/liveness_test/liveness_test_page.dart';

import 'liveness_provider_test.dart' show FakeLivenessService;

void main() {
  late FakeLivenessService service;

  setUp(() {
    service = FakeLivenessService()..config = {'enabled': 'true', 'maxAttempts': '3', 'testScreen': 'true'};
  });

  Widget app(Widget child) => ChangeNotifierProvider(
        create: (_) => LivenessProvider(service: service),
        child: MaterialApp(home: Scaffold(body: child)),
      );

  testWidgets('B-21 the entry is hidden unless the build has the test screen', (tester) async {
    service.config = {'enabled': 'true'};

    await tester.pumpWidget(app(const LivenessTestEntry()));
    await tester.pumpAndSettle();

    expect(find.text('Liveness test'), findsNothing);
  });

  testWidgets('B-21 the entry shows when the build has the test screen', (tester) async {
    await tester.pumpWidget(app(const LivenessTestEntry()));
    await tester.pumpAndSettle();

    expect(find.text('Liveness test'), findsOneWidget);
  });

  testWidgets('B-21 Start runs the check and shows the result', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();
    expect(find.text('Checking…'), findsOneWidget);

    service.pending!.complete(LivenessResultDto(
      finalState: 'FAILED',
      decision: 'INCONCLUSIVE',
      errorCode: 'LIV-009',
      messageKey: 'liveness.fail.try_again',
      challenges: ['SMILE:PASSED', 'TURN_LEFT:FAILED'],
      attempt: 1,
      maxAttempts: 3,
      retryAllowed: true,
    ));
    await tester.pumpAndSettle();

    expect(find.text('FAILED'), findsOneWidget);
    expect(find.text('LIV-009'), findsOneWidget);
    expect(find.text('SMILE:PASSED, TURN_LEFT:FAILED'), findsOneWidget);
    expect(find.text('1 of 3'), findsOneWidget);
  });

  testWidgets('B-21 the chosen scenario is sent before the check starts', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('liveness-test-scenario')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PAD').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();

    expect(service.testOverrides, 'enabled=true;stub.force=PAD');
    expect(service.starts, 1);
  });

  testWidgets('B-07 by default the check runs the real engine, with no forced scenario', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    expect(find.text('Real engine'), findsOneWidget);

    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();

    expect(service.testOverrides, 'enabled=true');
  });

  testWidgets('B-07 the real engine can be made to ask for challenges every time', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('liveness-test-scenario')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Real engine (challenges)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();

    expect(service.testOverrides, 'enabled=true;mode=ACTIVE');
    expect(service.starts, 1);
  });

  testWidgets('B-21 leaving the page while Start is still sending the scenario is safe', (tester) async {
    service.heldTestOverrides = Completer<void>();
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();

    await tester.pumpWidget(app(const SizedBox()));
    service.heldTestOverrides!.complete();
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(service.starts, 0);
  });

  testWidgets('B-21 the field is a test-only id for every workflow', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    expect(find.text('livenessTest'), findsOneWidget);

    await tester.tap(find.byKey(const Key('liveness-test-workflow')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OPERATOR_LOGIN').last);
    await tester.pumpAndSettle();

    expect(find.text('livenessTest'), findsOneWidget);
  });

  testWidgets('B-21 leaving the screen clears its overrides and its attempts', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();
    service.pending!.complete(LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE'));
    await tester.pumpAndSettle();

    await tester.pumpWidget(app(const SizedBox()));
    await tester.pumpAndSettle();

    expect(service.testOverrides, '');
    expect(service.resets, ['RESIDENT_REGISTRATION/livenessTest']);
  });

  testWidgets('B-21 the config is reloaded once the test overrides apply', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    final before = service.configCalls;

    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();

    expect(service.configCalls, before + 1);
  });

  testWidgets('B-21 a refused scenario hides the earlier result', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();
    service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));
    await tester.pumpAndSettle();

    service.testOverridesError = PlatformException(code: 'TEST_OVERRIDES_NOT_ALLOWED');
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pumpAndSettle();

    expect(find.text('PASSED'), findsNothing);
    expect(find.text('TEST_OVERRIDES_NOT_ALLOWED'), findsOneWidget);
  });

  testWidgets('B-21 a late config reply for an older workflow is ignored', (tester) async {
    service.heldConfigs = [];
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pump();
    await tester.tap(find.byKey(const Key('liveness-test-workflow')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OPERATOR_LOGIN').last);
    await tester.pumpAndSettle();

    service.heldConfigs![1].value.complete({'mode': 'ACTIVE'});
    await tester.pumpAndSettle();
    service.heldConfigs![0].value.complete({'mode': 'HYBRID'});
    await tester.pumpAndSettle();

    expect(find.text('ACTIVE'), findsOneWidget);
    expect(find.text('HYBRID'), findsNothing);
  });

  testWidgets('R2 seeding captures a face and saves it for the user', (tester) async {
    var captures = 0;
    await tester.pumpWidget(app(LivenessTestPage(seedCapture: () async => captures++)));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('liveness-test-seed-user')), '110011');
    await tester.ensureVisible(find.byKey(const Key('liveness-test-seed')));
    await tester.tap(find.byKey(const Key('liveness-test-seed')));
    await tester.pumpAndSettle();

    expect(captures, 1);
    expect(service.seeded, ['110011']);
    expect(find.text('Face template saved for 110011'), findsOneWidget);
  });

  testWidgets('R2 a refused seed shows the code', (tester) async {
    service.seedError = PlatformException(code: 'FACE_NO_CAPTURE');
    await tester.pumpWidget(app(LivenessTestPage(seedCapture: () async {})));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('liveness-test-seed-user')), '110011');
    await tester.ensureVisible(find.byKey(const Key('liveness-test-seed')));
    await tester.tap(find.byKey(const Key('liveness-test-seed')));
    await tester.pumpAndSettle();

    expect(find.text('FACE_NO_CAPTURE'), findsOneWidget);
  });

  testWidgets('B-21 Reset clears the result', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('liveness-test-start')));
    await tester.pump();
    service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE', attempt: 1, maxAttempts: 3));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('liveness-test-reset')));
    await tester.pumpAndSettle();

    expect(find.text('PASSED'), findsNothing);
    expect(service.resets, ['RESIDENT_REGISTRATION/livenessTest']);
  });
}
