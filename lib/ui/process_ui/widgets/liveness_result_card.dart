import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/utils/liveness_gate.dart';
import 'package:registration_client/utils/liveness_text.dart';

/// What the face liveness check of one field showed (UI_UX_SPEC §6.1, §6.3): S4 checking, S9 verified, S10/S11
/// failure with the attempts left, S12 attempts used up. Never shows codes, scores or the decision, so an attack
/// reads like any other failure. Empty before a check, after a cancel, or when liveness is off.
class LivenessResultCard extends StatelessWidget {
  const LivenessResultCard({super.key, required this.fieldId});

  final String fieldId;

  static const _workflow = LivenessGate.workflow;

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<LivenessProvider>();
    final l10n = AppLocalizations.of(context)!;
    if (provider.isChecking(_workflow, fieldId)) {
      return _line(context, Icons.hourglass_top, livenessText(l10n, 'liveness.status.checking'), null);
    }
    final error = provider.errorFor(_workflow, fieldId);
    if (error != null && error != 'LIVENESS_DISABLED') {
      return _line(context, Icons.error_outline, livenessText(l10n, 'liveness.error.engine'), Colors.red.shade700);
    }
    final result = provider.resultFor(_workflow, fieldId);
    if (result == null || result.finalState == 'CANCELLED') {
      return const SizedBox.shrink();
    }
    if (provider.passedFor(_workflow, fieldId)) {
      return _line(context, Icons.verified_user, livenessText(l10n, 'liveness.badge.verified'), Colors.green.shade700);
    }
    final message = livenessText(l10n, result.messageKey ?? 'liveness.fail.generic');
    final children = <Widget>[_line(context, Icons.error_outline, message, Colors.red.shade700)];
    if (provider.blockedFor(_workflow, fieldId)) {
      children.add(_ExceptionHint(workflow: _workflow));
    } else if (result.retryAllowed == true && result.attempt != null && result.maxAttempts != null) {
      children.add(Text(livenessText(l10n, 'liveness.retry.available', [result.attempt!, result.maxAttempts!])));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: children);
  }

  static Widget _line(BuildContext context, IconData icon, String text, Color? color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Flexible(child: Text(text, style: TextStyle(color: color))),
        ],
      ),
    );
  }
}

/// S12 with `on.max.retries=EXCEPTION_FLOW`: points the operator to the face exception. BLOCK, and
/// SUPERVISOR_OVERRIDE until the supervisor override exists (B-10), show nothing more.
class _ExceptionHint extends StatelessWidget {
  const _ExceptionHint({required this.workflow});

  final String workflow;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Map<String?, String?>>(
      future: context.read<LivenessProvider>().hints(workflow),
      builder: (context, hints) {
        if (hints.data?['onMaxRetries'] != 'EXCEPTION_FLOW') {
          return const SizedBox.shrink();
        }
        return Text(livenessText(AppLocalizations.of(context)!, 'liveness.button.mark_exception'));
      },
    );
  }
}
