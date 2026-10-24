import 'package:flutter/material.dart';
import 'package:registration_client/ui/widgets/face_auth_button.dart';

/// "Use face" for the supervisor's packet approval (B-10, UI_UX_SPEC §6.5): face authentication of the logged-in
/// supervisor under SUPERVISOR_EOD_AUTH (policy `eod_auth`), offered only to a supervisor. A match approves, as the
/// password does; the password stays.
class SupervisorFaceApproval extends StatelessWidget {
  const SupervisorFaceApproval({
    super.key,
    required this.userId,
    required this.isSupervisor,
    required this.onApproved,
    this.sbiCapture,
  });

  /// The liveness workflow of the end-of-day packet approval.
  static const workflow = 'SUPERVISOR_EOD_AUTH';

  final String userId;

  /// Whether the logged-in user has the supervisor role; nobody else may approve.
  final bool isSupervisor;

  /// Called once the supervisor's face matched.
  final VoidCallback onApproved;

  /// The SBI face capture; the real one by default, replaced in tests.
  final Future<void> Function()? sbiCapture;

  @override
  Widget build(BuildContext context) {
    if (!isSupervisor) {
      return const SizedBox.shrink();
    }
    return FaceAuthButton(userId: userId, workflow: workflow, onAuthenticated: onApproved, sbiCapture: sbiCapture);
  }
}
