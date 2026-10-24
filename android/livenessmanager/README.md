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

## Build flags (demo and test builds only)

These go in `android/local.properties`, which is not in git, or are passed with `-P`.

- `mosip.liveness.overrides`: liveness config pairs separated by `;`, for example `enabled=true;stub.force=PAD`. A key that doesn't start with `mosip.` gets the `mosip.registration.face.liveness.` prefix. They are applied after the synced config and the local preferences, so they win. Release builds honour them too, so a demo APK can turn liveness on while the server leaves it off. The app logs a warning when any are set.
- `mosip.liveness.testScreen=true`: gives a release build the liveness test screen (B-21). Debug builds always have it. It is reached from the login page, before any login, and lets anyone pick the stub scenario (for example `PAD`), which is fine on the demo phone. The screen clears its overrides and resets its attempts when it closes.

**Never set these flags for a build used in the field.**

## Known limitations

- **Attempt counters are kept in memory** (`LivenessSessionStore`). Restarting the app gives a fresh set of attempts, which weakens the max-retries limit (LIV-011) against someone who restarts the app on purpose. The fix is to persist the counters in the app's database; this is planned, not done.
- **The liveness gate is enforced natively only per capture** (#23: a fresh pass covers one face capture, for 120 s). It does not tie the capture to the live session; the capture binder (B-07) does that.
