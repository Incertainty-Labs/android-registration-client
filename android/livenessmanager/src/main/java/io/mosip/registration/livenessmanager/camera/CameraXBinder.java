package io.mosip.registration.livenessmanager.camera;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Size;
import android.view.Display;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Binds the front camera to the activity's lifecycle: a mirrored preview, and 640x480 {@code RGBA_8888}
 * analysis images that keep only the latest, so slow analysis never builds a backlog (TECHNICAL_DESIGN §4.3).
 * Binding waits for CameraX on the main thread's queue, never blocking it. Use cases follow the display's
 * rotation, so turning the tablet over to the resident keeps the frames upright (UI_UX_SPEC §8). Not testable
 * without a camera; checked on the phone.
 */
public final class CameraXBinder implements CameraXFrameSource.CameraBinder {

    private final ComponentActivity activity;
    private final PreviewView previewView;
    private final DisplayManager displays;
    private ProcessCameraProvider provider;
    private Preview preview;
    private ImageAnalysis analysis;
    private ExecutorService analysisThread;
    private boolean released;

    private final DisplayManager.DisplayListener rotationListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Display display = previewView.getDisplay();
            if (display != null && display.getDisplayId() == displayId) {
                applyRotation(display.getRotation());
            }
        }
    };

    /**
     * @param activity    owns the camera's lifecycle
     * @param previewView shows the preview
     */
    public CameraXBinder(ComponentActivity activity, PreviewView previewView) {
        this.activity = activity;
        this.previewView = previewView;
        this.displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    @Override
    public void bind(ImageAnalysis.Analyzer analyzer, Consumer<Exception> onFailure) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            if (released) {
                return;
            }
            try {
                provider = future.get();
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    throw new IllegalStateException("No front camera");
                }
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysisThread = Executors.newSingleThreadExecutor();
                analysis.setAnalyzer(analysisThread, analyzer);
                Display display = previewView.getDisplay();
                if (display != null) {
                    applyRotation(display.getRotation());
                }
                displays.registerDisplayListener(rotationListener, null);
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) {
                release();
                onFailure.accept(e);
            }
        }, activity.getMainExecutor());
    }

    @Override
    public void unbind() {
        released = true;
        release();
    }

    private void applyRotation(int rotation) {
        if (preview != null) {
            preview.setTargetRotation(rotation);
        }
        if (analysis != null) {
            analysis.setTargetRotation(rotation);
        }
    }

    /** Frees this binder's use cases and thread only; other camera users in the app are left alone. */
    private void release() {
        displays.unregisterDisplayListener(rotationListener);
        if (provider != null && preview != null && analysis != null) {
            provider.unbind(preview, analysis);
        }
        if (analysis != null) {
            analysis.clearAnalyzer();
        }
        if (analysisThread != null) {
            analysisThread.shutdown();
            analysisThread = null;
        }
        preview = null;
        analysis = null;
    }
}
