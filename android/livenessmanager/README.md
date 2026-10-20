# livenessmanager

Android side of PS04 face liveness: CameraX frame source, liveness screen and results, on top of `liveness-core` and `face-verification` (`mosip-face-liveness`). Models it ships: [MODELS.md](MODELS.md).

## Tests

Build on `develop` with JDK 17 (AGP 7.2.2 / Gradle 7.5), from the main clone, not a git worktree.

Unit tests (JVM):

```bash
cd android
./gradlew :livenessmanager:testDebugUnitTest
```

Results go to `build/livenessmanager/test-results/` at the repository root (Flutter moves Gradle's build directory).

Instrumented tests (device, USB debugging on):

```bash
cd android
./gradlew :livenessmanager:assembleDebugAndroidTest
adb install -r -t ../build/livenessmanager/outputs/apk/androidTest/debug/livenessmanager-debug-androidTest.apk
adb shell am instrument -w -r io.mosip.registration.livenessmanager.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s YuNetOnDeviceTest:I   # ms per inference
```

Use `am instrument`, not `connectedDebugAndroidTest`: under AGP 7.2 the Gradle task installs and runs the tests on Android 15 but reports 0 tests.

The face test needs a consented photo at `src/androidTest/assets/consented/b-selfie.jpg` and is skipped without one; photos are never committed ([CONSENT.md](src/androidTest/CONSENT.md)).
