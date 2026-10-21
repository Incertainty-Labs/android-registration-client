# Models shipped in livenessmanager

Files in `src/main/assets/models/`, copied from `mosip-face-liveness/models/` (see its `models.json`).

| File | Model | Version | SHA-256 | Licence | Source |
| --- | --- | --- | --- | --- | --- |
| `face_detection_yunet_2023mar.onnx` | YuNet face detector (box + 5 landmarks); input 1x3x640x640, BGR, 0-255 | 2023mar | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | MIT | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |

The remaining models (MiniFASNet, landmarks, SFace) are added with the real engine (B-07), loaded through `ModelRegistry` with hash checks.
