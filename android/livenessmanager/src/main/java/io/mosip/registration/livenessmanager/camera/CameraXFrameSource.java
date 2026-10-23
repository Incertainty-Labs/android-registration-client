package io.mosip.registration.livenessmanager.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.FrameListener;
import io.mosip.registration.liveness.api.FrameSource;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.api.SourceCapabilities;

/**
 * The tablet's front camera as a liveness {@link FrameSource} (TECHNICAL_DESIGN §4.3). CameraX hands each
 * {@code RGBA_8888} analysis image to {@link #analyze}, which turns it into an upright RGB {@link FaceFrame}.
 * {@link #analyze} runs on the analysis thread; {@link #start} and {@link #stop} on the main thread.
 */
public final class CameraXFrameSource implements FrameSource, ImageAnalysis.Analyzer {

    /** Binds and unbinds the CameraX use cases; {@code LivenessActivity} does it against its lifecycle. */
    public interface CameraBinder {

        /**
         * Binds the front camera's image analysis, with {@code analyzer} receiving every image. Binding may finish
         * later, off this call, so as not to block the main thread.
         *
         * @param analyzer  receives the analysis images
         * @param onFailure told if binding fails after this call returned
         * @throws Exception if the camera cannot be bound
         */
        void bind(ImageAnalysis.Analyzer analyzer, Consumer<Exception> onFailure) throws Exception;

        /** Releases what {@link #bind} bound, also after a failed bind. Safe to call twice. */
        void unbind();
    }

    static final String ID = "camerax:front";
    static final String CERTIFICATION = "L0-camera";
    private static final String FORMAT = "RGBA_8888";

    /**
     * The frame rate CameraX delivers analysis images at on phones and tablets; reported as nominal so a session
     * can plan before the first frames are measured.
     */
    static final double NOMINAL_FPS = 30.0;

    private static final int RGBA_PIXEL_STRIDE = 4;

    /** The measured frame rate covers this much of the most recent frames (SourceCapabilities). */
    private static final long FPS_WINDOW_MS = 2000L;

    /** Unreadable frames in a row before LIV-012 (TECHNICAL_DESIGN §4.5). */
    private static final int MAX_UNREADABLE = 5;

    private final Context context;
    private final CameraBinder binder;
    private final RgbaFrameConverter converter = new RgbaFrameConverter(ID);
    private final ArrayDeque<Long> recentFrameMs = new ArrayDeque<>();
    private volatile FrameListener listener;
    private boolean bound;
    private int unreadable;
    private int width;
    private int height;

    /**
     * @param context checks the camera permission
     * @param binder  binds the camera when the source starts
     */
    public CameraXFrameSource(Context context, CameraBinder binder) {
        this.context = context;
        this.binder = binder;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public synchronized SourceCapabilities capabilities() {
        return SourceCapabilities.builder()
                .device(Build.MANUFACTURER, Build.MODEL)
                .certification(CERTIFICATION)
                .resolution(width, height)
                .fps(NOMINAL_FPS, measuredFps())
                .devicePad(false)
                .formats(Collections.singleton(FORMAT))
                .build();
    }

    /**
     * @throws LivenessException LIV-003 if the camera permission is missing or the camera cannot be bound
     */
    @Override
    public void start(FrameListener frameListener) throws LivenessException {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new LivenessException(LivenessErrorCode.CAMERA_UNAVAILABLE, "Camera permission not granted");
        }
        listener = frameListener;
        try {
            binder.bind(this, this::bindFailed);
        } catch (Exception e) {
            listener = null;
            binder.unbind();
            throw new LivenessException(LivenessErrorCode.CAMERA_UNAVAILABLE, "Camera bind failed: " + e.getMessage());
        }
        bound = true;
    }

    @Override
    public void stop() {
        listener = null;
        if (bound) {
            bound = false;
            binder.unbind();
        }
    }

    private void bindFailed(Exception e) {
        FrameListener target = listener;
        if (target != null) {
            target.onError(LivenessErrorCode.CAMERA_UNAVAILABLE, "Camera bind failed: " + e.getMessage());
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            FrameListener target = listener;
            if (target == null) {
                return;
            }
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            FaceFrame frame;
            try {
                if (plane.getPixelStride() != RGBA_PIXEL_STRIDE) {
                    throw new IllegalArgumentException("Not packed RGBA: pixel stride " + plane.getPixelStride());
                }
                frame = converter.convert(plane.getBuffer(), image.getWidth(), image.getHeight(),
                        plane.getRowStride(), image.getImageInfo().getRotationDegrees(),
                        TimeUnit.NANOSECONDS.toMillis(image.getImageInfo().getTimestamp()));
            } catch (IllegalArgumentException e) {
                unreadable++;
                if (unreadable == MAX_UNREADABLE) {
                    target.onError(LivenessErrorCode.INVALID_FRAME_DATA, e.getMessage());
                }
                return;
            }
            unreadable = 0;
            record(frame);
            target.onFrame(frame);
        } finally {
            image.close();
        }
    }

    private synchronized void record(FaceFrame frame) {
        width = frame.width();
        height = frame.height();
        recentFrameMs.addLast(frame.timestampMs());
        while (frame.timestampMs() - recentFrameMs.peekFirst() > FPS_WINDOW_MS) {
            recentFrameMs.removeFirst();
        }
    }

    private double measuredFps() {
        if (recentFrameMs.size() < 2) {
            return 0.0;
        }
        long span = recentFrameMs.peekLast() - recentFrameMs.peekFirst();
        return span <= 0 ? 0.0 : (recentFrameMs.size() - 1) * 1000.0 / span;
    }
}
