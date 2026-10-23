package io.mosip.registration.livenessmanager.camera;

import android.util.Size;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Binds the front camera to the activity's lifecycle: a mirrored preview, and 640x480 {@code RGBA_8888}
 * analysis images that keep only the latest, so slow analysis never builds a backlog (TECHNICAL_DESIGN §4.3).
 * Not testable without a camera; checked on the phone.
 */
public final class CameraXBinder implements CameraXFrameSource.CameraBinder {

    /** How long {@link #bind} waits for CameraX to initialise. */
    private static final long PROVIDER_TIMEOUT_S = 5L;

    private final ComponentActivity activity;
    private final PreviewView previewView;
    private ProcessCameraProvider provider;
    private ExecutorService analysisThread;

    /**
     * @param activity    owns the camera's lifecycle
     * @param previewView shows the preview
     */
    public CameraXBinder(ComponentActivity activity, PreviewView previewView) {
        this.activity = activity;
        this.previewView = previewView;
    }

    @Override
    public void bind(ImageAnalysis.Analyzer analyzer) throws Exception {
        provider = ProcessCameraProvider.getInstance(activity).get(PROVIDER_TIMEOUT_S, TimeUnit.SECONDS);
        if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            throw new IllegalStateException("No front camera");
        }
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysisThread = Executors.newSingleThreadExecutor();
        analysis.setAnalyzer(analysisThread, analyzer);
        provider.unbindAll();
        provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
    }

    @Override
    public void unbind() {
        if (provider != null) {
            provider.unbindAll();
        }
        if (analysisThread != null) {
            analysisThread.shutdown();
        }
    }
}
