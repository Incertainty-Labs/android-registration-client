import 'package:flutter/material.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';
import 'package:provider/provider.dart';
import 'package:registration_client/provider/liveness_provider.dart';
import 'package:registration_client/ui/process_ui/widgets/liveness_result_card.dart';
import 'package:registration_client/utils/face_auth.dart';
import 'package:registration_client/utils/liveness_text.dart';
import 'package:registration_client/utils/sbi_capture_error.dart';

/// "Use face" (UI_UX_SPEC §6.4): face authentication for [userId], offered above the password only when liveness is
/// on for [workflow] and a face matcher is configured. The password stays.
class FaceAuthButton extends StatefulWidget {
  const FaceAuthButton({
    super.key,
    required this.userId,
    required this.workflow,
    required this.onAuthenticated,
    this.sbiCapture,
  });

  final String userId;
  final String workflow;

  /// Called once the face matched.
  final VoidCallback onAuthenticated;

  /// The SBI face capture; the real one by default, replaced in tests.
  final Future<void> Function()? sbiCapture;

  @override
  State<FaceAuthButton> createState() => _FaceAuthButtonState();
}

class _FaceAuthButtonState extends State<FaceAuthButton> {
  late final LivenessProvider _provider = context.read<LivenessProvider>();
  late final Future<bool> _available = _isAvailable();
  bool _busy = false;
  bool _authenticated = false;
  String? _messageKey;

  Future<bool> _isAvailable() async {
    final hints = await _provider.hints(widget.workflow);
    return hints['enabled'] == 'true' && hints['faceAuthAvailable'] == 'true';
  }

  Future<void> _authenticate() async {
    setState(() {
      _busy = true;
      _messageKey = null;
    });
    try {
      final outcome =
          await FaceAuth(_provider, workflow: widget.workflow, sbiCapture: widget.sbiCapture).run(widget.userId);
      if (!mounted) {
        return;
      }
      setState(() {
        _authenticated = outcome.authenticated;
        _messageKey = outcome.messageKey;
      });
      if (outcome.authenticated) {
        widget.onAuthenticated();
      }
    } catch (e) {
      if (mounted) {
        showSbiCaptureError(context, e);
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<bool>(
      future: _available,
      builder: (context, available) {
        if (available.data != true) {
          return const SizedBox.shrink();
        }
        final l10n = AppLocalizations.of(context)!;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            OutlinedButton.icon(
              key: const Key('face-auth-button'),
              icon: const Icon(Icons.face),
              label: Text(livenessText(l10n, 'liveness.button.use_face')),
              onPressed: _busy || _authenticated ? null : _authenticate,
            ),
            LivenessResultCard(fieldId: FaceAuth.fieldId, workflow: widget.workflow),
            if (_messageKey != null)
              Text(livenessText(l10n, _messageKey!), style: TextStyle(color: Colors.red.shade700)),
          ],
        );
      },
    );
  }
}
