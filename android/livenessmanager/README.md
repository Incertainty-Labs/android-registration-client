# livenessmanager

Android side of PS04 face liveness: CameraX frame source, liveness screen and results, on top of `liveness-core` and `face-verification` (`mosip-face-liveness`). Models it ships: [MODELS.md](MODELS.md).

## Tests

Build on `master` with JDK 21 (AGP 8.3.2 / Gradle 8.5), from the main clone, not a git worktree. On Windows keep the Pub cache on the project's drive (`PUB_CACHE=D:\pub-cache` for a project on `D:`); otherwise Gradle 8 fails with "this and base files have different roots".

Unit tests (JVM):

```bash
cd android
./gradlew :livenessmanager:testDebugUnitTest
```

Results go to `build/livenessmanager/test-results/` at the repository root (Flutter moves Gradle's build directory).

Instrumented tests (device, USB debugging on):

```bash
cd android
./gradlew :livenessmanager:connectedDebugAndroidTest
adb logcat -d -s YuNetOnDeviceTest:I   # ms per inference
```

Results go to `build/livenessmanager/outputs/androidTest-results/connected/`. These tests install only the separate test package `io.mosip.registration.livenessmanager.test`, never the app.

Without Gradle, the same run by hand:

```bash
./gradlew :livenessmanager:assembleDebugAndroidTest
adb install -r -t ../build/livenessmanager/outputs/apk/androidTest/debug/livenessmanager-debug-androidTest.apk
adb shell am instrument -w -r io.mosip.registration.livenessmanager.test/androidx.test.runner.AndroidJUnitRunner
```

(On the old `develop` base, AGP 7.2's `connectedDebugAndroidTest` reported 0 tests on Android 15; `am instrument` was the workaround.)

The face test needs a consented photo at `src/androidTest/assets/consented/b-selfie.jpg` and is skipped without one; photos are never committed ([CONSENT.md](src/androidTest/CONSENT.md)).
