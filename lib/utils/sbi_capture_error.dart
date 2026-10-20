import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_gen/gen_l10n/app_localizations.dart';

/// How a biometric capture through the SBI app ended without a result.
enum SbiCaptureError {
  cancelled,
  deviceNotFound,
  failed;

  /// Classifies the error thrown by `BiometricsApi().invokeDiscoverSbi`.
  static SbiCaptureError from(Object error) {
    final code = error is PlatformException ? error.code : null;
    switch (code) {
      case 'REG-SBI-118':
        return SbiCaptureError.cancelled;
      case 'REG-SBI-104':
        return SbiCaptureError.deviceNotFound;
      default:
        return SbiCaptureError.failed;
    }
  }

  /// The message shown to the operator.
  String message(AppLocalizations l10n) {
    switch (this) {
      case SbiCaptureError.cancelled:
        return l10n.sbi_capture_cancelled;
      case SbiCaptureError.deviceNotFound:
        return l10n.sbi_device_not_found;
      case SbiCaptureError.failed:
        return l10n.sbi_capture_failed;
    }
  }
}

/// Shows why a capture through the SBI app ended without a result.
void showSbiCaptureError(BuildContext context, Object error) {
  debugPrint('SBI capture ended without a result: $error');
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text(SbiCaptureError.from(error).message(AppLocalizations.of(context)!))),
  );
}
