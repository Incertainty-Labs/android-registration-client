import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/ui/approve_packet/widget/supervisor_face_approval.dart';

import 'liveness_provider_test.dart' show FakeLivenessService;

void main() {
  const supervisor = '220022';

  late FakeLivenessService service;
  late LivenessProvider provider;

  setUp(() {
    service = FakeLivenessService()..config = {'enabled': 'true', 'faceAuthAvailable': 'true'};
    provider = LivenessProvider(service: service);
  });

  Future<void> pump(WidgetTester tester, {required bool isSupervisor, required VoidCallback onApproved}) async {
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: provider,
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('en'),
        home: Scaffold(
          body: SupervisorFaceApproval(
            userId: supervisor,
            isSupervisor: isSupervisor,
            onApproved: onApproved,
            sbiCapture: () async {},
          ),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> useFace(WidgetTester tester) async {
    await tester.tap(find.text('Use face'));
    await tester.pump();
    service.pending!.complete(LivenessResultDto(finalState: 'PASSED', decision: 'LIVE'));
    await tester.pumpAndSettle();
  }

  testWidgets('TC-AND-12 only a supervisor is offered face approval', (tester) async {
    await pump(tester, isSupervisor: false, onApproved: () {});

    expect(find.text('Use face'), findsNothing);
  });

  testWidgets('TC-AND-12 a supervisor whose face matches approves the packets', (tester) async {
    var approved = 0;
    await pump(tester, isSupervisor: true, onApproved: () => approved++);

    await useFace(tester);

    expect(approved, 1);
    expect(service.lastWorkflow, 'SUPERVISOR_EOD_AUTH');
    expect(service.authCalls, ['SUPERVISOR_EOD_AUTH/$supervisor']);
  });

  testWidgets('TC-AND-12 another face approves nothing', (tester) async {
    var approved = 0;
    service.authResult = AuthResultDto(
        authenticated: false, errorCode: 'FACE_NOT_MATCHED', messageKey: 'liveness.auth.no_match');
    await pump(tester, isSupervisor: true, onApproved: () => approved++);

    await useFace(tester);

    expect(approved, 0);
  });
}
