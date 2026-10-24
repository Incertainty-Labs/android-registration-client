import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/provider/liveness_provider.dart';

/// The liveness workflows (liveness-core `Workflow`).
const livenessTestWorkflows = [
  'RESIDENT_REGISTRATION',
  'OPERATOR_ONBOARDING',
  'OPERATOR_LOGIN',
  'OPERATOR_PACKET_AUTH',
  'SUPERVISOR_EXCEPTION_AUTH',
  'SUPERVISOR_EOD_AUTH',
];

/// The stub engine's scripted scenarios (liveness-core `StubScenario`).
const livenessTestScenarios = [
  'PASS_ACTIVE',
  'PASS_PASSIVE',
  'NO_FACE',
  'FAIL_CHALLENGE',
  'TIMEOUT',
  'PAD',
  'ENGINE_ERROR',
];

/// A button that opens [LivenessTestPage]; shown only when the build has the test screen (B-21).
class LivenessTestEntry extends StatelessWidget {
  const LivenessTestEntry({super.key});

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: context.read<LivenessProvider>().testScreenAllowed(),
      builder: (context, allowed) {
        if (allowed.data != true) {
          return const SizedBox.shrink();
        }
        return TextButton.icon(
          icon: const Icon(Icons.face_retouching_natural),
          label: const Text('Liveness test'),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => const Scaffold(body: SafeArea(child: LivenessTestPage())),
          )),
        );
      },
    );
  }
}

/// Developer screen (B-21): runs a liveness check for any workflow and shows the full result, without login.
/// English only; it is not part of the product UI.
class LivenessTestPage extends StatefulWidget {
  const LivenessTestPage({super.key});

  @override
  State<LivenessTestPage> createState() => _LivenessTestPageState();
}

class _LivenessTestPageState extends State<LivenessTestPage> {
  /// The screen's own field id, so its attempts and results never touch a real field's.
  static const testField = 'livenessTest';

  String workflow = livenessTestWorkflows.first;
  String? scenario;
  final TextEditingController field = TextEditingController(text: testField);
  Map<String?, String?> hints = {};
  late final LivenessProvider _provider;

  /// The workflow/field pairs this screen ran, so their attempts are reset when it closes.
  final Set<MapEntry<String, String>> _ran = {};

  /// Why the build refused the chosen scenario, or null.
  String? refused;

  @override
  void initState() {
    super.initState();
    _provider = context.read<LivenessProvider>();
    _loadHints();
  }

  /// Leaves nothing behind: later real checks run without the screen's overrides, and the attempts it used are
  /// cleared.
  @override
  void dispose() {
    _provider.clearTestOverrides();
    for (final pair in _ran) {
      _provider.resetAttempts(pair.key, pair.value);
    }
    field.dispose();
    super.dispose();
  }

  Future<void> _loadHints() async {
    final requested = workflow;
    final loaded = await _provider.hints(requested);
    // A reply for a workflow that is no longer selected is out of date
    if (mounted && requested == workflow) {
      setState(() => hints = loaded);
    }
  }

  Future<void> _start() async {
    final code = await _provider.useTestScenario(scenario);
    setState(() => refused = code);
    if (code == null) {
      _loadHints();
      _ran.add(MapEntry(workflow, field.text));
      await _provider.start(workflow, field.text);
    }
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<LivenessProvider>();
    final error = refused ?? provider.errorFor(workflow, field.text);
    final result = provider.resultFor(workflow, field.text);
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Liveness test', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 16),
        DropdownButtonFormField<String>(
          key: const Key('liveness-test-workflow'),
          value: workflow,
          decoration: const InputDecoration(labelText: 'Workflow'),
          items: [for (final w in livenessTestWorkflows) DropdownMenuItem(value: w, child: Text(w))],
          onChanged: (value) {
            setState(() => workflow = value!);
            _loadHints();
          },
        ),
        TextField(controller: field, decoration: const InputDecoration(labelText: 'Field id')),
        DropdownButtonFormField<String?>(
          key: const Key('liveness-test-scenario'),
          value: scenario,
          decoration: const InputDecoration(labelText: 'Stub scenario'),
          items: [
            const DropdownMenuItem<String?>(value: null, child: Text('Build default')),
            for (final s in livenessTestScenarios) DropdownMenuItem<String?>(value: s, child: Text(s)),
          ],
          onChanged: (value) => setState(() => scenario = value),
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            ElevatedButton(
              key: const Key('liveness-test-start'),
              onPressed: provider.status == LivenessStatus.checking ? null : _start,
              child: const Text('Start'),
            ),
            const SizedBox(width: 16),
            OutlinedButton(
              key: const Key('liveness-test-reset'),
              onPressed: () => provider.resetAttempts(workflow, field.text),
              child: const Text('Reset attempts'),
            ),
          ],
        ),
        const SizedBox(height: 16),
        _section(context, 'Config', {
          for (final e in hints.entries) e.key ?? '': e.value ?? '',
        }),
        if (provider.status == LivenessStatus.checking) const Text('Checking…'),
        if (error != null) _section(context, 'Error', {'code': error}),
        if (refused == null && result != null) _section(context, 'Result', _resultRows(result)),
      ],
    );
  }

  static Map<String, String> _resultRows(LivenessResultDto r) => {
        'finalState': r.finalState ?? '',
        'decision': r.decision ?? '',
        'errorCode': r.errorCode ?? '',
        'messageKey': r.messageKey ?? '',
        'challenges': (r.challenges ?? []).join(', '),
        'attempt': '${r.attempt} of ${r.maxAttempts}',
        'retryAllowed': '${r.retryAllowed}',
        'passiveScore': r.passiveScore?.toString() ?? '',
      };

  static Widget _section(BuildContext context, String title, Map<String, String> rows) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          for (final row in rows.entries)
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(width: 120, child: Text(row.key)),
                Expanded(child: Text(row.value)),
              ],
            ),
        ],
      ),
    );
  }
}
