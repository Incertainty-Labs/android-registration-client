import 'package:flutter/material.dart';
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

  testWidgets('B-21 the field defaults per workflow', (tester) async {
    await tester.pumpWidget(app(const LivenessTestPage()));
    await tester.pumpAndSettle();
    expect(find.text('individualBiometrics'), findsOneWidget);

    await tester.tap(find.byKey(const Key('liveness-test-workflow')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OPERATOR_LOGIN').last);
    await tester.pumpAndSettle();

    expect(find.text('operatorAuth'), findsOneWidget);
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
    expect(service.resets, ['RESIDENT_REGISTRATION/individualBiometrics']);
  });
}
