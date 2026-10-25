# Face liveness for the Android Registration Client (MOSIP Decode PS04)

This fork adds **face liveness detection** (presentation attack detection, PAD) to every face capture in the Android Registration Client (ARC). A person must prove they are live in front of the phone's camera before ARC asks the SBI for the face. A printed photo, a phone screen or a replayed video is refused before any biometric is captured.

The engine is the shared `liveness-core` library from [mosip-face-liveness](https://github.com/Incertainty-Labs/mosip-face-liveness), which the Desktop client uses too. It runs fully on the device, offline, on the CPU.

## What it does

| Where | Liveness workflow | What the person sees |
| --- | --- | --- |
| Resident face capture (new, update, lost) | `RESIDENT_REGISTRATION` | A camera screen with an oval and prompts before the SBI capture |
| Resident exception photo | `RESIDENT_EXCEPTION_PHOTO` | A passive check only: no challenges, as the person may not be able to do them |
| Operator onboarding face | `OPERATOR_ONBOARDING` | As for residents, with its own attempt count |
| Operator and supervisor face authentication | `OPERATOR_PACKET_AUTH`, `SUPERVISOR_EOD_AUTH` | Liveness, then the SBI capture, then a 1:1 match (test screen builds only; see Known limits) |

**How a check runs.** The default mode is **HYBRID**:
1. A quiet passive check scores 8 frames with two MiniFASNet PAD models. The person only looks at the camera.
2. If that passes, the person holds still for about 2 seconds, and the check passes.
3. If the passive check is unsure, the engine asks for 2 or 3 random challenges, checked against the person's own neutral face: blink, close the eyes, smile, open the mouth, turn left or right, or look up.
4. A frozen or replayed stream is vetoed, and a second face fails the check.

A typical real face passes in about 3 seconds; with challenges it takes about 8.

**Fails closed.** Each of these means no face capture:
- a missing or altered model file;
- an engine that cannot start (LIV-013);
- a failed check;
- used-up attempts (LIV-011).

There is never a fallback to "no liveness". A native guard also refuses an SBI face capture (REG-SBI-121) unless a fresh, unused liveness pass for that field covers it. Calling the SBI directly from Dart therefore cannot skip the check.

**Fresh captures only.** Each SBI capture request carries the liveness session's nonce as its `transactionId`. The SBI signs it back, together with its timestamp. ARC checks both after verifying the signature: the nonce must be this session's, and the capture must be signed within `bind.freshness.max.age.ms` (60 s) of the pass. A replayed or late capture fails with REG-LIV-014, counts as a failed attempt and is audited. Captures with liveness off, such as fingerprint and iris, get a fresh nonce per request, checked against the request time.

**Evidence and audit.**
- **Packet evidence.** Each face BIR in the packet carries these keys from the check that allowed the capture:
  - `LIVENESS_DECISION`, `LIVENESS_SCORE`, `LIVENESS_THRESHOLD`, `LIVENESS_MODE`;
  - `LIVENESS_CHALLENGES`, `LIVENESS_ENGINE_VERSION`, `LIVENESS_EVIDENCE_DIGEST`.
- **Audit.** Every session is audited as `REG-EVT-115` to `REG-EVT-125`: started, passive passed, active triggered, challenge passed, failed or timed out, PAD suspected, passed, failed, max retries, and engine error.
- **Privacy.** No frames or face images are stored or sent anywhere.

## Quick start (install on a phone)

You need:
- an Android phone, Android 9 (API 28) or newer, with a front camera;
- `adb`;
- the ARC APK from this fork's GitHub Release;
- an Android SBI app that answers `io.sbi.device`.

For the mock SBI, build `MockAndroidSBI` from [mosip/android-camera-mds](https://github.com/mosip/android-camera-mds) (branch `develop`). It needs device and FTM keys trusted by your MOSIP server; load them in its Settings, then press **Load and validate certificates**.

1. `adb install -r app-release.apk`. Use `-r` to update an existing install. **Never uninstall** a registered ARC: its machine keys are registered with the server.
2. Open ARC, grant the camera permission when asked, and log in as an operator. On the login page, the **Liveness test** button opens the test screen in builds that have it.
3. Start a new registration and capture the face. The liveness screen opens first; after a pass, the SBI capture runs.

**Without a MOSIP server:**
1. Open **Liveness test** from the login page and choose the workflow.
2. Under **Engine**, choose:
   - **Real engine**: the default HYBRID check;
   - **Real engine (challenges)**: ACTIVE mode, with challenges every time;
   - a scripted stub scenario (`PASS_ACTIVE`, `PAD`, `TIMEOUT` and others) to see each screen state.
3. Tap **Start**. The screen shows the effective config and the full result.

## Build from source

Prerequisites:
- Flutter 3.10.x;
- JDK 21;
- Android SDK 34;
- network access to JitPack, which serves `liveness-core` and `face-verification` (tag in `android/build.gradle`, `livenessVersion`).

Set these in `android/local.properties` (git-ignored):

| Key | Meaning |
| --- | --- |
| `mosip.serverBaseURL` | Your MOSIP server, for example a Collab environment |
| `mosip.liveness.testScreen=true` | Gives a release build the Liveness test screen. Debug builds always have it. |
| `mosip.liveness.overrides` | Optional build-time liveness config, for example `enabled=true` to turn liveness on where the server leaves it off |

Then build:

```sh
flutter pub get
cd android
./gradlew :app:assembleRelease -Ptarget-platform=android-arm64
# APK: build/app/outputs/apk/release/app-release.apk
```

**Use a release build for anything you time or demo.** Debuggable builds run the engine's Java about 10 times slower: about 830 ms per frame against about 31 ms. The release build type is signed with the debug key unless you configure your own in `key.properties`.

The model bundle ships in `android/livenessmanager/src/main/assets/models/`, about 47 MB, uncompressed in the APK. See `android/livenessmanager/MODELS.md` for each model, its source, licence and SHA-256. The engine refuses the bundle if any hash does not match.

## Configuration

Liveness is configured with MOSIP global config keys under the prefix `mosip.registration.face.liveness.`. Any key can be set for one workflow as `mosip.registration.face.liveness.policy.<code>.<key>`. The codes are `registration`, `exception_photo`, `onboard_auth`, `login_auth`, `packet_auth`, `exception_auth` and `eod_auth`.

**Precedence:** synced server config < `mosip.liveness.overrides` in the build < local preferences. A key changed on the device must also be permitted by the server (`permitted_local_config`).

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Turns liveness on |
| `mode` | `HYBRID` | `PASSIVE`, `ACTIVE` or `HYBRID`. The exception photo is `PASSIVE` unless its own policy key says otherwise. |
| `passive.threshold` | `0.80` | Minimum passive live score |
| `passive.min.frames` | `8` | Frames the passive check needs |
| `active.enabled` | `true` | Whether HYBRID may escalate to challenges |
| `challenge.pool` | all | Challenges to draw from |
| `challenge.min.count` / `challenge.max.count` | `2` / `3` | Challenges per check |
| `challenge.timeout.ms` | `5000` | Time per challenge |
| `session.timeout.ms` | `30000` | Time per check |
| `blink.min.fps` | `15` | Camera rate needed to offer BLINK |
| `max.retries` | `3` | Attempts per field |
| `on.max.retries` | `BLOCK` | `BLOCK`, `EXCEPTION_FLOW` or `SUPERVISOR_OVERRIDE` |
| `detector.min.score` | `0.70` | Face detector confidence |
| `quality.min.face.width` | `0.18` | Minimum face width, as a share of the frame |
| `bind.freshness.max.age.ms` | `60000` | Maximum age of the SBI capture after the pass |

The thresholds are the engine's defaults. The calibrated values from the evaluation run will replace them here when they are published.

**Engine speed options** are fixed in `AssetModelLoader.OPTIONS`: CPU, 2 threads, and the face detector on every 3rd frame. They were measured as the fastest on the test phone; see Performance.

## Testing

**Unit tests**, run from `android/`:

```sh
./gradlew :livenessmanager:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :clientmanager:testDebugUnitTest
```

From the root, run the Flutter liveness tests:

```sh
flutter test test/liveness_gate_test.dart test/liveness_result_card_test.dart test/liveness_provider_test.dart test/liveness_test_page_test.dart
```

**On the phone.** `EngineOnDeviceTest` builds the engine from the shipped bundle and logs a speed table for every provider, thread count and detector interval:

```sh
./gradlew :livenessmanager:connectedReleaseAndroidTest -PlivenessTestBuildType=release \
  -Pandroid.testInstrumentationRunnerArguments.class=io.mosip.registration.livenessmanager.engine.EngineOnDeviceTest
adb logcat -s EngineOnDeviceTest:I
```

The face timing needs a consented photo at `android/livenessmanager/src/androidTest/assets/consented/b-selfie.jpg`. It is kept out of git, and the test is skipped without it.

Results per test case are in the project's test report (`docs/testing/TEST_REPORT_ANDROID.md` in mosip-face-liveness).

## Performance

Measured on a Nothing A059 (Snapdragon 7s Gen 3, 8 cores, Android 15), with a release build.

| Measure | Result |
| --- | --- |
| Engine start-up (models checked and loaded) | about 0.3 s, in the background at app start |
| Frame with face detection, including both PAD models | about 64 ms |
| Frame that follows the face | about 14 ms |
| Frames analysed per second | about 22 to 27, live |
| Passive check | about 1 s |
| Real face, HYBRID, face found to pass | about 3 s |
| Real face with 3 challenges | about 8 s |

On the CPU, XNNPACK was no faster, and NNAPI was 4 to 5 times slower.

## Known limits

- **The face match binder is off.** The engine can bind the SBI capture to the live session by matching the two faces. That needs the SFace matcher in `face-verification`, which is future work; the mock SBI also returns a fixed bundled face, so a match could not prove anything in a demo. Freshness binding (Fresh captures only, above) does not need the matcher and is on.
- **Face authentication is offered only in test screen builds.** Operator and supervisor face auth (liveness, SBI capture, 1:1 match through the BioSDK) works. Without the binder, though, the matched face is not proven to be the live one, so field builds keep password authentication.
- **The mock SBI returns a fixed face.** With MockAndroidSBI, the captured image is not the person in front of the phone. Liveness still checks the real person on the phone's own camera.
- **Attempt counts are kept in memory.** They reset when the app restarts. The same goes for a capture's freshness ticket: if the app is killed between the SBI request and its response, that capture fails with REG-LIV-014.
- **The SBI has 60 s.** With liveness on, the SBI must sign the capture within 60 s of the pass. A capture left open longer fails with REG-LIV-014; check again.
- **HYBRID rarely shows challenges for a real face.** Challenges appear only when the passive check is unsure. Use ACTIVE mode, or "Real engine (challenges)" on the test screen, to see them.
