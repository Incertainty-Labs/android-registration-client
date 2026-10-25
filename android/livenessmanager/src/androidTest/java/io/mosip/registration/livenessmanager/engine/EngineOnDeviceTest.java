package io.mosip.registration.livenessmanager.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OrtSession;

import io.mosip.registration.liveness.api.EngineInfo;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.FaceObservation;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.config.LivenessConfigLoader;
import io.mosip.registration.liveness.engine.onnx.MiniFasNetPadDetector;
import io.mosip.registration.liveness.engine.onnx.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.engine.onnx.Sessions;
import io.mosip.registration.liveness.engine.onnx.TrackingFaceAnalyzer;
import io.mosip.registration.liveness.model.ModelRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * B-07 on a real phone: the shipped model bundle passes its checks and builds the hybrid engine with ONNX Runtime
 * Android, and the per-frame models find the one face in a consented photo under every engine option. Logs the
 * engine's load time and the speed table, for TC-PERF and the engine's defaults. Time it on a release build
 * ({@code -PlivenessTestBuildType=release}): debuggable builds run the engine's Java far slower.
 */
@RunWith(AndroidJUnit4.class)
public class EngineOnDeviceTest {

    private static final String TAG = "EngineOnDeviceTest";
    private static final String PHOTO_ASSET = "consented/b-selfie.jpg";

    @Test
    public void b07_shippedBundle_buildsTheHybridEngine() throws Exception {
        long start = SystemClock.elapsedRealtime();
        LivenessEngine engine = AssetModelLoader.fromAssets(context()).load();
        long loadMs = SystemClock.elapsedRealtime() - start;

        EngineInfo info = engine.info();
        Log.i(TAG, String.format("engine %s built in %d ms on %s (debuggable %b); models %s", info.engineVersion(),
                loadMs, info.executionProvider(), debuggable(context()), info.modelVersions()));
        assertTrue(info.modelVersions().keySet().containsAll(java.util.Arrays.asList(
                "yunet", "facemesh_v2", "blendshape_v2", "minifasnet_v2", "minifasnet_v1se")));
    }

    /**
     * The speed table for the engine's options (v0.2.1): every provider, thread count and detector interval, on the
     * consented photo with the engine's own session settings and detector score. Each row: 5 warm-up frames, then
     * 30 timed; mean and p90 of the analysis on detection and on followed frames, and of both PAD models on the
     * detection frames (the only frames they run on).
     */
    @Test
    public void b07_speedTable_perProviderThreadsAndDetectorInterval() throws Exception {
        Context context = context();
        assumeTrue("no consented photo at " + PHOTO_ASSET + " (kept out of git; see CONSENT.md)",
                hasAsset(context, PHOTO_ASSET));
        ModelRegistry registry = AssetModelLoader.fromAssets(context).registry();
        FaceFrame frame = frameOf(BitmapFactory.decodeStream(context.getAssets().open(PHOTO_ASSET)));
        Log.i(TAG, String.format("debuggable %b, %d cores, Android %s (API %d), %s", debuggable(context),
                Runtime.getRuntime().availableProcessors(), Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.MODEL));
        Log.i(TAG, "provider | threads | detectEvery | analyze detection mean/p90 | analyze followed mean/p90"
                + " | PAD mean/p90 (ms)");

        for (String requested : new String[] {"CPU", "XNNPACK", "NNAPI"}) {
            for (int threads : new int[] {0, 2, 4}) {
                for (int detectEvery : new int[] {1, 3}) {
                    String provider = Sessions.effectiveProvider(requested);
                    List<Double> detection = new ArrayList<>();
                    List<Double> followed = new ArrayList<>();
                    List<Double> pad = new ArrayList<>();
                    try (OrtSession.SessionOptions options = Sessions.options(provider, threads);
                         OnnxFaceAnalyzer analyzer = OnnxFaceAnalyzer.load(registry,
                                 LivenessConfigLoader.DEFAULT_DETECTOR_MIN_SCORE, options);
                         MiniFasNetPadDetector v2 = MiniFasNetPadDetector.load(registry, "minifasnet_v2", 2.7,
                                 options);
                         MiniFasNetPadDetector v1se = MiniFasNetPadDetector.load(registry, "minifasnet_v1se", 4.0,
                                 options)) {
                        TrackingFaceAnalyzer tracker = detectEvery > 1 ? analyzer.tracking(detectEvery) : null;
                        FaceAnalyzer perSession = tracker != null ? tracker : analyzer;
                        for (int i = 0; i < WARM_UP + TIMED_FRAMES; i++) {
                            long begin = System.nanoTime();
                            FaceObservation seen = perSession.analyze(frame);
                            long analysed = System.nanoTime();
                            boolean detected = tracker == null || tracker.detected();
                            if (detected) {
                                v2.score(frame, seen);
                                v1se.score(frame, seen);
                            }
                            long scored = System.nanoTime();
                            assertEquals("faces on frame " + i, 1, seen.faceCount());
                            if (i < WARM_UP) {
                                continue;
                            }
                            (detected ? detection : followed).add((analysed - begin) / 1e6);
                            if (detected) {
                                pad.add((scored - analysed) / 1e6);
                            }
                        }
                    }
                    Log.i(TAG, String.format("%s | %d | %d | %s | %s | %s", provider, threads, detectEvery,
                            stats(detection), stats(followed), stats(pad)));
                }
            }
        }
    }

    private static final int WARM_UP = 5;
    private static final int TIMED_FRAMES = 30;

    /** @return "mean/p90" in ms, or "-" when there were none */
    private static String stats(List<Double> ms) {
        if (ms.isEmpty()) {
            return "-";
        }
        List<Double> sorted = new ArrayList<>(ms);
        Collections.sort(sorted);
        double sum = 0;
        for (double v : sorted) {
            sum += v;
        }
        double p90 = sorted.get((int) Math.ceil(0.9 * sorted.size()) - 1);
        return String.format("%.1f/%.1f", sum / sorted.size(), p90);
    }

    private static boolean debuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Scales the photo to 640 px on its long side, as the camera's analysis stream is, and packs it as RGB. */
    private static FaceFrame frameOf(Bitmap photo) {
        float scale = 640f / Math.max(photo.getWidth(), photo.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(photo, Math.round(photo.getWidth() * scale),
                Math.round(photo.getHeight() * scale), true);
        int w = scaled.getWidth();
        int h = scaled.getHeight();
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0; i < pixels.length; i++) {
            rgb[3 * i] = (byte) Color.red(pixels[i]);
            rgb[3 * i + 1] = (byte) Color.green(pixels[i]);
            rgb[3 * i + 2] = (byte) Color.blue(pixels[i]);
        }
        return new FaceFrame(w, h, rgb, SystemClock.elapsedRealtime(), 1, "consented-photo");
    }

    private static boolean hasAsset(Context context, String name) {
        try (InputStream ignored = context.getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
