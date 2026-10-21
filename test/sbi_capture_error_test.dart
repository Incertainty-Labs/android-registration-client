import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:registration_client/utils/sbi_capture_error.dart';

void main() {
  test('TC-AND-14: a cancelled SBI capture maps to cancelled', () {
    expect(
      SbiCaptureError.from(PlatformException(code: 'REG-SBI-118', message: 'Capture cancelled')),
      SbiCaptureError.cancelled,
    );
  });

  test('TC-AND-16: a missing SBI app maps to device not found', () {
    expect(
      SbiCaptureError.from(PlatformException(code: 'REG-SBI-104', message: 'Device not found')),
      SbiCaptureError.deviceNotFound,
    );
  });

  test('TC-AND-14: a second Scan while a capture runs maps to in progress', () {
    expect(
      SbiCaptureError.from(PlatformException(code: 'REG-SBI-119', message: 'Capture already in progress')),
      SbiCaptureError.inProgress,
    );
  });

  test('TC-AND-14: any other capture error maps to failed', () {
    for (final error in [
      PlatformException(code: 'REG-SBI-115', message: 'RCapture Failed! '),
      PlatformException(code: 'REG-SBI-106', message: 'Device response with invalid signature'),
      StateError('not a platform error'),
    ]) {
      expect(SbiCaptureError.from(error), SbiCaptureError.failed, reason: '$error');
    }
  });
}
