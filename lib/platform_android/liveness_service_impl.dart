import 'package:registration_client/pigeon/liveness_pigeon.dart';
import 'package:registration_client/platform_spi/liveness_service.dart';

class LivenessServiceImpl implements LivenessService {
  @override
  Future<LivenessResultDto> startLiveness(String workflow, String fieldId) =>
      LivenessApi().startLiveness(workflow, fieldId);

  @override
  Future<Map<String?, String?>> getLivenessConfig(String workflow) =>
      LivenessApi().getLivenessConfig(workflow);

  @override
  Future<void> resetAttempts(String workflow, String fieldId) =>
      LivenessApi().resetAttempts(workflow, fieldId);

  @override
  Future<void> setTestOverrides(String overrides) => LivenessApi().setTestOverrides(overrides);
}

LivenessService getLivenessServiceImpl() => LivenessServiceImpl();
