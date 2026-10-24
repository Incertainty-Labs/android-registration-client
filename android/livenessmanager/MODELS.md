# Models shipped in livenessmanager

Files in `src/main/assets/models/`, copied from `mosip-face-liveness/models/` (see its `models.json`).

| File | Model | Version | SHA-256 | Licence | Source |
| --- | --- | --- | --- | --- | --- |
| `face_detection_yunet_2023mar.onnx` | YuNet face detector (box + 5 landmarks); input 1x3x640x640, BGR, 0-255 | 2023mar | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | MIT | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |
| `facemesh_v2_256.onnx` | MediaPipe Face Mesh V2 (478 landmarks), ONNX by PINTO model zoo | 2022sep | `6d7932bdefc38871f57dd915b8c723d855e599f29cf4cdf19616fb35d0ed572e` | Apache-2.0 | https://github.com/PINTO0309/PINTO_model_zoo/tree/main/410_FaceMeshV2 |
| `blendshape_v2_146x2.onnx` | MediaPipe face blendshapes | 2022nov | `79065a18016da3b95f71247ff9ade3fe09b9124903a26a1af85af6d9e2a4faf3` | Apache-2.0 | see `models.json` |
| `minifasnet_v2_2.7_80x80.onnx` | MiniFASNet V2 passive PAD | see `models.json` | see `models.json` | Apache-2.0 | see `models.json` |
| `minifasnet_v1se_4.0_80x80.onnx` | MiniFASNet V1SE passive PAD | see `models.json` | see `models.json` | Apache-2.0 | see `models.json` |
| `face_recognition_sface_2021dec.onnx` | SFace face recognition (for the B-07 match binder, `face-verification`) | 2021dec | see `models.json` | Apache-2.0 | see `models.json` |

`models.json` (bundle 1.0.0) is copied unchanged from `mosip-face-liveness` v0.2.0. `ModelRegistry` checks every file's SHA-256 against it at start; any mismatch or missing file refuses the whole bundle (LIV-013), with no fallback. Every file the manifest lists must be shipped, SFace included (about 37 MB; the B-07 match binder uses it). The ONNX files are stored uncompressed in the APK (`noCompress 'onnx'`).
