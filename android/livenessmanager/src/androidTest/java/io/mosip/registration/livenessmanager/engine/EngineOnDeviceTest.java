package io.mosip.registration.livenessmanager.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

import io.mosip.registration.liveness.api.EngineInfo;
import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.FaceObservation;
import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.PadScore;
import io.mosip.registration.liveness.engine.onnx.MiniFasNetPadDetector;
import io.mosip.registration.liveness.engine.onnx.OnnxFaceAnalyzer;
import io.mosip.registration.liveness.model.ModelRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * B-07 on a real phone: the shipped model bundle passes its checks and builds the hybrid engine with ONNX Runtime
 * Android, and the per-frame models find the one face in a consented photo. Logs the engine's load time and the
 * milliseconds per frame (face analysis plus both PAD models), for TC-PERF and the engine's budget.
 */
@RunWith(AndroidJUnit4.class)
public class EngineOnDeviceTest {

    private static final String TAG = "EngineOnDeviceTest";
    private static final String PHOTO_ASSET = "consented/b-selfie.jpg";
    private static final int TIMED_RUNS = 10;

    @Test
    public void b07_shippedBundle_buildsTheHybridEngine() throws Exception {
        long start = SystemClock.elapsedRealtime();
        LivenessEngine engine = AssetModelLoader.fromAssets(context()).load();
        long loadMs = SystemClock.elapsedRealtime() - start;

        EngineInfo info = engine.info();
        Log.i(TAG, String.format("engine %s built in %d ms on %s; models %s", info.engineVersion(), loadMs,
                info.executionProvider(), info.modelVersions()));
        assertTrue(info.modelVersions().keySet().containsAll(java.util.Arrays.asList(
                "yunet", "facemesh_v2", "blendshape_v2", "minifasnet_v2", "minifasnet_v1se")));
    }

    @Test
    public void b07_perFrameModels_findTheOneFaceInAConsentedPhoto() throws Exception {
        Context context = context();
        assumeTrue("no consented photo at " + PHOTO_ASSET + " (kept out of git; see CONSENT.md)",
                hasAsset(context, PHOTO_ASSET));
        ModelRegistry registry = AssetModelLoader.fromAssets(context).registry();
        FaceFrame frame = frameOf(BitmapFactory.decodeStream(context.getAssets().open(PHOTO_ASSET)));

        try (OnnxFaceAnalyzer analyzer = OnnxFaceAnalyzer.load(registry, 0.6);
             MiniFasNetPadDetector v2 = MiniFasNetPadDetector.load(registry, "minifasnet_v2", 2.7);
             MiniFasNetPadDetector v1se = MiniFasNetPadDetector.load(registry, "minifasnet_v1se", 4.0)) {
            FaceObservation seen = analyzer.analyze(frame);
            PadScore first = v2.score(frame, seen);
            v1se.score(frame, seen);

            long analysisNs = 0;
            long padNs = 0;
            for (int i = 0; i < TIMED_RUNS; i++) {
                long begin = SystemClock.elapsedRealtimeNanos();
                FaceObservation observation = analyzer.analyze(frame);
                long analysed = SystemClock.elapsedRealtimeNanos();
                v2.score(frame, observation);
                v1se.score(frame, observation);
                analysisNs += analysed - begin;
                padNs += SystemClock.elapsedRealtimeNanos() - analysed;
            }
            double analysisMs = analysisNs / 1e6 / TIMED_RUNS;
            double padMs = padNs / 1e6 / TIMED_RUNS;
            Log.i(TAG, String.format("%dx%d frame: %.1f ms = analysis %.1f + 2 PAD %.1f (%d runs); minifasnet_v2 live"
                            + " %.3f", frame.width(), frame.height(), analysisMs + padMs, analysisMs, padMs, TIMED_RUNS,
                    first.liveProbability()));

            assertEquals(1, seen.faceCount());
        }
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
