package io.mosip.registration.livenessmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * B-03 "hello model": the shipped YuNet model runs in ONNX Runtime Android on a real device and finds the one face
 * in a consented photo. Logs milliseconds per inference. The YuNet decoding here is a test helper only; the engine's
 * own detector comes with A-08.
 */
@RunWith(AndroidJUnit4.class)
public class YuNetOnDeviceTest {

    private static final String TAG = "YuNetOnDeviceTest";
    private static final String MODEL_ASSET = "models/face_detection_yunet_2023mar.onnx";
    private static final String PHOTO_ASSET = "consented/b-selfie.jpg";
    private static final int SIZE = 640;
    private static final int[] STRIDES = {8, 16, 32};
    private static final float SCORE_THRESHOLD = 0.9f;
    private static final float NMS_IOU = 0.3f;
    private static final int TIMED_RUNS = 10;

    /**
     * Needs a consented photo of exactly one face at {@code src/androidTest/assets/consented/b-selfie.jpg}. Face
     * photos are kept out of git (see CONSENT.md), so this test is skipped when the photo is absent.
     */
    @Test
    public void b03_yunetOnDevice_findsOneFaceInConsentedPhoto() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        assumeTrue("no consented photo at " + PHOTO_ASSET + " (kept out of git; see CONSENT.md)",
                hasAsset(context, PHOTO_ASSET));
        byte[] model = readAsset(context, MODEL_ASSET);
        Bitmap photo = BitmapFactory.decodeStream(context.getAssets().open(PHOTO_ASSET));

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        int faces;
        try (OrtSession session = env.createSession(model, new OrtSession.SessionOptions());
             OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(letterboxBgr(photo)),
                     new long[] {1, 3, SIZE, SIZE})) {
            String inputName = session.getInputNames().iterator().next();
            session.run(Collections.singletonMap(inputName, input)).close();

            long start = SystemClock.elapsedRealtimeNanos();
            for (int i = 0; i < TIMED_RUNS; i++) {
                session.run(Collections.singletonMap(inputName, input)).close();
            }
            double msPerRun = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / TIMED_RUNS;
            Log.i(TAG, String.format("YuNet %dx%d: %.1f ms per inference (%d runs)", SIZE, SIZE, msPerRun, TIMED_RUNS));

            try (OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
                faces = countFaces(result);
            }
        }

        assertEquals(1, faces);
    }

    @Test
    public void b03_yunetOnDevice_findsNoFaceInBlankImage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Bitmap blank = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
        new Canvas(blank).drawColor(Color.BLACK);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession(readAsset(context, MODEL_ASSET), new OrtSession.SessionOptions());
             OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(letterboxBgr(blank)),
                     new long[] {1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(
                     Collections.singletonMap(session.getInputNames().iterator().next(), input))) {
            assertEquals(0, countFaces(result));
        }
    }

    /** Scales the photo into the top-left of a black 640x640 square and returns planar BGR floats, 0..255. */
    private static float[] letterboxBgr(Bitmap photo) {
        float scale = Math.min((float) SIZE / photo.getWidth(), (float) SIZE / photo.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(photo, Math.round(photo.getWidth() * scale),
                Math.round(photo.getHeight() * scale), true);
        Bitmap square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(square);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(scaled, 0, 0, null);

        int[] pixels = new int[SIZE * SIZE];
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        float[] chw = new float[3 * SIZE * SIZE];
        int plane = SIZE * SIZE;
        for (int i = 0; i < plane; i++) {
            chw[i] = Color.blue(pixels[i]);
            chw[plane + i] = Color.green(pixels[i]);
            chw[2 * plane + i] = Color.red(pixels[i]);
        }
        return chw;
    }

    /** YuNet 2023mar: one prior per cell; score = sqrt(cls * obj); greedy NMS over the boxes above the threshold. */
    private static int countFaces(OrtSession.Result result) {
        List<float[]> candidates = new ArrayList<>();
        for (int stride : STRIDES) {
            FloatBuffer cls = ((OnnxTensor) result.get("cls_" + stride).get()).getFloatBuffer();
            FloatBuffer obj = ((OnnxTensor) result.get("obj_" + stride).get()).getFloatBuffer();
            FloatBuffer bbox = ((OnnxTensor) result.get("bbox_" + stride).get()).getFloatBuffer();
            int cols = SIZE / stride;
            for (int i = 0; i < cols * cols; i++) {
                float score = (float) Math.sqrt(clamp01(cls.get(i)) * clamp01(obj.get(i)));
                if (score < SCORE_THRESHOLD) {
                    continue;
                }
                float cx = (i % cols + bbox.get(4 * i)) * stride;
                float cy = (i / cols + bbox.get(4 * i + 1)) * stride;
                float w = (float) Math.exp(bbox.get(4 * i + 2)) * stride;
                float h = (float) Math.exp(bbox.get(4 * i + 3)) * stride;
                candidates.add(new float[] {cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score});
            }
        }
        Collections.sort(candidates, (a, b) -> Float.compare(b[4], a[4]));
        List<float[]> kept = new ArrayList<>();
        for (float[] box : candidates) {
            boolean overlaps = false;
            for (float[] k : kept) {
                if (iou(box, k) > NMS_IOU) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(box);
            }
        }
        return kept.size();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float iou(float[] a, float[] b) {
        float w = Math.max(0f, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
        float h = Math.max(0f, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        float inter = w * h;
        float union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
        return union <= 0f ? 0f : inter / union;
    }

    private static boolean hasAsset(Context context, String name) {
        try (InputStream ignored = context.getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
