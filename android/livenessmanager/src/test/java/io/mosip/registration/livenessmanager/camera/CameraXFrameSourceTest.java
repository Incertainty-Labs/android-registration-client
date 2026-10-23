package io.mosip.registration.livenessmanager.camera;

import android.Manifest;
import android.app.Application;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.mosip.registration.liveness.api.FaceFrame;
import io.mosip.registration.liveness.api.FrameListener;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.api.SourceCapabilities;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CameraXFrameSourceTest {

    private Application app;
    private FakeBinder binder;
    private Recorder listener;
    private CameraXFrameSource source;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        binder = new FakeBinder();
        listener = new Recorder();
        source = new CameraXFrameSource(app, binder);
    }

    @Test
    public void tcAnd_id_isTheFrontCamera() {
        assertEquals("camerax:front", source.id());
    }

    @Test
    public void tcAnd_capabilities_areAnL0CameraWithRgbaFramesAndNoDevicePad() {
        SourceCapabilities caps = source.capabilities();

        assertEquals("L0-camera", caps.certification());
        assertEquals(Collections.singleton("RGBA_8888"), caps.formats());
        assertFalse(caps.devicePad());
        assertEquals(0.0, caps.measuredFps(), 0.0);
    }

    @Test
    public void tcAnd15_startWithoutCameraPermission_throwsLiv003AndDoesNotBind() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);

        LivenessException error = startExpectingError();

        assertEquals(LivenessErrorCode.CAMERA_UNAVAILABLE, error.errorCode());
        assertNull(binder.analyzer);
    }

    @Test
    public void tcAnd15_bindFailure_throwsLiv003WithTheCause() {
        binder.failure = new IllegalStateException("No front camera");

        LivenessException error = startExpectingError();

        assertEquals(LivenessErrorCode.CAMERA_UNAVAILABLE, error.errorCode());
        assertTrue(error.getMessage(), error.getMessage().contains("No front camera"));
    }

    @Test
    public void tcAnd_analysedImage_reachesTheListenerAsAnUprightRgbFrame() throws Exception {
        source.start(listener);
        // 2x1 image: red, green; rotated 90 degrees it becomes 1x2
        ImageProxy image = image(new byte[]{1, 2, 3, 9, 4, 5, 6, 9}, 2, 1, 8, 90, 1_500_000_000L);

        binder.analyzer.analyze(image);

        assertEquals(1, listener.frames.size());
        FaceFrame frame = listener.frames.get(0);
        assertEquals(1, frame.width());
        assertEquals(2, frame.height());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, frame.rgb());
        assertEquals(1500L, frame.timestampMs());
        assertEquals("camerax:front", frame.sourceId());
        verify(image).close();
    }

    @Test
    public void tcAnd_frames_areNumberedInOrder() throws Exception {
        source.start(listener);

        binder.analyzer.analyze(goodImage(0L));
        binder.analyzer.analyze(goodImage(100L));

        assertEquals(0L, listener.frames.get(0).sequence());
        assertEquals(1L, listener.frames.get(1).sequence());
    }

    @Test
    public void tcAnd_capabilities_reportTheFrameSizeAndMeasuredFps() throws Exception {
        source.start(listener);

        for (long t = 0; t <= 1000; t += 100) {
            binder.analyzer.analyze(goodImage(t));
        }

        SourceCapabilities caps = source.capabilities();
        assertEquals(10.0, caps.measuredFps(), 0.01);
        assertEquals(2, caps.width());
        assertEquals(1, caps.height());
    }

    @Test
    public void tcAnd_measuredFps_countsOnlyTheLastTwoSeconds() throws Exception {
        source.start(listener);

        for (long t = 0; t < 2000; t += 200) {
            binder.analyzer.analyze(goodImage(t));
        }
        for (long t = 2000; t <= 4000; t += 50) {
            binder.analyzer.analyze(goodImage(t));
        }

        assertEquals(20.0, source.capabilities().measuredFps(), 0.01);
    }

    @Test
    public void tcAnd_fiveUnreadableFramesInARow_reportLiv012Once() throws Exception {
        source.start(listener);

        for (int i = 0; i < 6; i++) {
            ImageProxy bad = badImage(i * 100L);
            binder.analyzer.analyze(bad);
            verify(bad).close();
        }

        assertTrue(listener.frames.isEmpty());
        assertEquals(Collections.singletonList(LivenessErrorCode.INVALID_FRAME_DATA), listener.errors);
    }

    @Test
    public void tcAnd_aGoodFrame_resetsTheUnreadableCount() throws Exception {
        source.start(listener);

        for (int i = 0; i < 4; i++) {
            binder.analyzer.analyze(badImage(i * 100L));
        }
        binder.analyzer.analyze(goodImage(400L));
        for (int i = 0; i < 4; i++) {
            binder.analyzer.analyze(badImage(500L + i * 100L));
        }

        assertTrue(listener.errors.isEmpty());
        assertEquals(1, listener.frames.size());
    }

    @Test
    public void tcAnd_stop_unbindsOnceAndDropsLaterImages() throws Exception {
        source.start(listener);
        ImageAnalysis.Analyzer analyzer = binder.analyzer;

        source.stop();
        source.stop();
        ImageProxy late = goodImage(0L);
        analyzer.analyze(late);

        assertEquals(1, binder.unbinds);
        assertTrue(listener.frames.isEmpty());
        verify(late).close();
    }

    @Test
    public void tcAnd_stopBeforeStart_doesNotUnbind() {
        source.stop();

        assertEquals(0, binder.unbinds);
    }

    @Test
    public void tcAnd_start_bindsThisSourceAsTheAnalyzer() throws Exception {
        source.start(listener);

        assertSame(source, binder.analyzer);
    }

    @Test
    public void tcAnd_capabilities_reportANominalFpsBeforeAnyFrame() {
        assertEquals(30.0, source.capabilities().nominalFps(), 0.0);
    }

    @Test
    public void tcAnd15_bindFailure_releasesWhatWasBound() {
        binder.failure = new IllegalStateException("No front camera");
        startExpectingError();

        source.stop();

        assertEquals(1, binder.unbinds);
    }

    @Test
    public void tcAnd15_laterBindFailure_reportsLiv003() throws Exception {
        source.start(listener);

        binder.onFailure.accept(new IllegalStateException("Camera in use"));

        assertEquals(Collections.singletonList(LivenessErrorCode.CAMERA_UNAVAILABLE), listener.errors);
    }

    @Test
    public void tcAnd15_laterBindFailureAfterStop_isIgnored() throws Exception {
        source.start(listener);
        source.stop();

        binder.onFailure.accept(new IllegalStateException("Camera in use"));

        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void tcAnd_imageWithAnotherPixelStride_isUnreadable() throws Exception {
        source.start(listener);
        ImageProxy image = goodImage(0L);
        when(image.getPlanes()[0].getPixelStride()).thenReturn(3);

        binder.analyzer.analyze(image);

        assertTrue(listener.frames.isEmpty());
        verify(image).close();
    }

    private LivenessException startExpectingError() {
        try {
            source.start(listener);
            fail("start should have thrown");
            return null;
        } catch (LivenessException e) {
            return e;
        }
    }

    /** A valid 2x1 RGBA image with no rotation. */
    private static ImageProxy goodImage(long timestampMs) {
        return image(new byte[8], 2, 1, 8, 0, TimeUnit.MILLISECONDS.toNanos(timestampMs));
    }

    /** A 2x1 image whose plane is too short to read. */
    private static ImageProxy badImage(long timestampMs) {
        return image(new byte[3], 2, 1, 8, 0, TimeUnit.MILLISECONDS.toNanos(timestampMs));
    }

    private static ImageProxy image(byte[] rgba, int width, int height, int rowStride, int rotation,
                                    long timestampNs) {
        ImageProxy.PlaneProxy plane = mock(ImageProxy.PlaneProxy.class);
        when(plane.getBuffer()).thenReturn(ByteBuffer.wrap(rgba));
        when(plane.getRowStride()).thenReturn(rowStride);
        when(plane.getPixelStride()).thenReturn(4);
        ImageInfo info = mock(ImageInfo.class);
        when(info.getRotationDegrees()).thenReturn(rotation);
        when(info.getTimestamp()).thenReturn(timestampNs);
        ImageProxy image = mock(ImageProxy.class);
        when(image.getWidth()).thenReturn(width);
        when(image.getHeight()).thenReturn(height);
        when(image.getPlanes()).thenReturn(new ImageProxy.PlaneProxy[]{plane});
        when(image.getImageInfo()).thenReturn(info);
        return image;
    }

    private static final class FakeBinder implements CameraXFrameSource.CameraBinder {
        private ImageAnalysis.Analyzer analyzer;
        private Consumer<Exception> onFailure;
        private RuntimeException failure;
        private int unbinds;

        @Override
        public void bind(ImageAnalysis.Analyzer value, Consumer<Exception> failed) {
            if (failure != null) {
                throw failure;
            }
            analyzer = value;
            onFailure = failed;
        }

        @Override
        public void unbind() {
            unbinds++;
        }
    }

    private static final class Recorder implements FrameListener {
        private final List<FaceFrame> frames = new ArrayList<>();
        private final List<LivenessErrorCode> errors = new ArrayList<>();

        @Override
        public void onFrame(FaceFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onError(LivenessErrorCode code, String detail) {
            errors.add(code);
        }

        @Override
        public void onEnd() {
        }
    }
}
