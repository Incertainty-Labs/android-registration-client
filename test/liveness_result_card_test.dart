import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/ui/process_ui/widgets/liveness_result_card.dart';

import 'liveness_provider_test.dart' show FakeLivenessService;

void main() {
  const workflow = 'RESIDENT_REGISTRATION';
  const face = 'individualBiometrics';

  late FakeLivenessService service;
  late LivenessProvider provider;

  setUp(() {
    service = FakeLivenessService()..config = {'enabled': 'true', 'onMaxRetries': 'BLOCK'};
    provider = LivenessProvider(service: service);
  });

  Future<void> pumpCard(WidgetTester tester, {String fieldId = face}) async {
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: provider,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Scaffold(body: LivenessResultCard(fieldId: fieldId)),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> finish(WidgetTester tester, Object outcome) async {
    final check = provider.start(workflow, face);
    if (outcome is LivenessResultDto) {
      service.pending!.complete(outcome);
    } else {
      service.pending!.completeError(outcome);
    }
    await check;
    await tester.pumpAndSettle();
  }

  testWidgets('TC-AND-05 nothing shows before a check', (tester) async {
    await pumpCard(tester);

    expect(find.byType(Text), findsNothing);
  });

  testWidgets('TC-AND-05 S4 shows while the field is checked, only on its own card', (tester) async {
    await pumpCard(tester);
    provider.start(workflow, face);
    await tester.pump();

    expect(find.text('Checking face liveness…'), findsOneWidget);

    await pumpCard(tester, fieldId: 'introducerBiometrics');
    expect(find.text('Checking face liveness…'), findsNothing);
    service.pending!.complete(LivenessResultDto(finalState: 'CANCELLED', decision: 'INCONCLUSIVE'));
  });

  testWidgets('TC-AND-05 S9 a pass shows the verified badge', (tester) async {
    await pumpCard(tester);

    await finish(tester, LivenessResultDto(
        finalState: 'PASSED', decision: 'LIVE', messageKey: 'liveness.feedback.success', attempt: 1, maxAttempts: 3));

    expect(find.text('Liveness: verified'), findsOneWidget);
  });

  testWidgets('TC-AND-06 S10 a failure shows its message and the attempts left', (tester) async {
    await pumpCard(tester);

    await finish(tester, LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE', errorCode: 'LIV-009',
        messageKey: 'liveness.fail.try_again', attempt: 1, maxAttempts: 3, retryAllowed: true));

    expect(find.text('We could not verify face liveness. Please try again.'), findsOneWidget);
    expect(find.text('Attempt 1 of 3. Select Retry to try again.'), findsOneWidget);
  });

  testWidgets('TC-AND-06 S11 an attack shows only the generic failure', (tester) async {
    await pumpCard(tester);

    await finish(tester, LivenessResultDto(finalState: 'FAILED', decision: 'ATTACK_SUSPECTED', errorCode: 'LIV-008',
        messageKey: 'liveness.fail.generic', attempt: 1, maxAttempts: 3, retryAllowed: true));

    expect(find.text('Face verification could not be completed. Please try again.'), findsOneWidget);
    expect(find.textContaining('LIV-008'), findsNothing);
    expect(find.textContaining('ATTACK'), findsNothing);
  });

  testWidgets('TC-AND-07 S12 used-up attempts show the max-retries message (BLOCK)', (tester) async {
    await pumpCard(tester);

    await finish(tester, LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE', errorCode: 'LIV-011',
        messageKey: 'liveness.fail.max_retries', attempt: 3, maxAttempts: 3, retryAllowed: false));

    expect(find.text('Face verification could not be completed. Please contact your supervisor.'), findsOneWidget);
    expect(find.textContaining('Attempt'), findsNothing);
    expect(find.text('Mark face exception'), findsNothing);
  });

  testWidgets('TC-AND-07 S12 with EXCEPTION_FLOW also points to the face exception', (tester) async {
    service.config = {'enabled': 'true', 'onMaxRetries': 'EXCEPTION_FLOW'};
    await pumpCard(tester);

    await finish(tester, LivenessResultDto(finalState: 'FAILED', decision: 'INCONCLUSIVE', errorCode: 'LIV-011',
        messageKey: 'liveness.fail.max_retries', attempt: 3, maxAttempts: 3, retryAllowed: false));

    expect(find.text('Mark face exception'), findsOneWidget);
  });

  testWidgets('TC-AND-06 a check that cannot start shows the engine message', (tester) async {
    await pumpCard(tester);

    await finish(tester, PlatformException(code: 'LIVENESS_START_FAILED'));

    expect(find.text('Face verification is unavailable. Please contact support.'), findsOneWidget);
  });

  testWidgets('TC-AND-05 liveness off and a cancel show nothing', (tester) async {
    await pumpCard(tester);

    await finish(tester, PlatformException(code: 'LIVENESS_DISABLED'));
    expect(find.byType(Text), findsNothing);

    await finish(tester, LivenessResultDto(finalState: 'CANCELLED', decision: 'INCONCLUSIVE'));
    expect(find.byType(Text), findsNothing);
  });
}
