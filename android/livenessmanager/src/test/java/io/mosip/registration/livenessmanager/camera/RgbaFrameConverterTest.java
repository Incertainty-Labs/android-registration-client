package io.mosip.registration.livenessmanager.camera;

import org.junit.Test;

import java.nio.ByteBuffer;

import io.mosip.registration.liveness.api.FaceFrame;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RgbaFrameConverterTest {

    @Test
    public void tcAnd01_withUnpaddedUprightImage_dropsAlphaAndKeepsPixelOrder() {
        RgbaFrameConverter converter = new RgbaFrameConverter("camerax:front");
        ByteBuffer rgba = ByteBuffer.wrap(bytes(
                10, 20, 30, 255, 40, 50, 60, 255));

        FaceFrame frame = converter.convert(rgba, 2, 1, 8, 0, 1500L);

        assertEquals(2, frame.width());
        assertEquals(1, frame.height());
        assertArrayEquals(bytes(10, 20, 30, 40, 50, 60), frame.rgb());
        assertEquals(1500L, frame.timestampMs());
        assertEquals("camerax:front", frame.sourceId());
    }

    // A 3x2 image, rows padded to 16 bytes:   A B C
    //                                          D E F
    private static final int A = 10, B = 20, C = 30, D = 40, E = 50, F = 60;
    private static final int PADDED_STRIDE = 3 * 4 + 4;

    @Test
    public void tcAnd01_withRotation90_turnsImageClockwiseAndSwapsSize() {
        RgbaFrameConverter converter = new RgbaFrameConverter("camerax:front");

        FaceFrame frame = converter.convert(paddedImage(), 3, 2, PADDED_STRIDE, 90, 0L);

        assertEquals(2, frame.width());
        assertEquals(3, frame.height());
        assertArrayEquals(rgb(D, A, E, B, F, C), frame.rgb());
    }

    @Test
    public void tcAnd01_withRotation180_turnsImageUpsideDown() {
        RgbaFrameConverter converter = new RgbaFrameConverter("camerax:front");

        FaceFrame frame = converter.convert(paddedImage(), 3, 2, PADDED_STRIDE, 180, 0L);

        assertEquals(3, frame.width());
        assertEquals(2, frame.height());
        assertArrayEquals(rgb(F, E, D, C, B, A), frame.rgb());
    }

    @Test
    public void tcAnd01_withRotation270_turnsImageAnticlockwiseAndSwapsSize() {
        RgbaFrameConverter converter = new RgbaFrameConverter("camerax:front");

        FaceFrame frame = converter.convert(paddedImage(), 3, 2, PADDED_STRIDE, 270, 0L);

        assertEquals(2, frame.width());
        assertEquals(3, frame.height());
        assertArrayEquals(rgb(C, F, B, E, A, D), frame.rgb());
    }

    @Test(expected = IllegalArgumentException.class)
    public void tcAnd01_withRotationNotAQuarterTurn_isRejected() {
        new RgbaFrameConverter("camerax:front").convert(paddedImage(), 3, 2, PADDED_STRIDE, 45, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void tcAnd01_withTruncatedPlane_isRejected() {
        ByteBuffer oneRowOnly = ByteBuffer.allocate(PADDED_STRIDE);

        new RgbaFrameConverter("camerax:front").convert(oneRowOnly, 3, 2, PADDED_STRIDE, 0, 0L);
    }

    @Test
    public void tcAnd01_withLastRowPaddingOmitted_convertsAllPixels() {
        ByteBuffer full = paddedImage();
        ByteBuffer withoutLastPadding = ByteBuffer.allocate(PADDED_STRIDE + 3 * 4);
        full.limit(withoutLastPadding.capacity());
        withoutLastPadding.put(full).rewind();

        FaceFrame frame = new RgbaFrameConverter("camerax:front")
                .convert(withoutLastPadding, 3, 2, PADDED_STRIDE, 0, 0L);

        assertArrayEquals(rgb(A, B, C, D, E, F), frame.rgb());
    }

    @Test
    public void tcAnd01_withSuccessiveImages_numbersFramesInOrder() {
        RgbaFrameConverter converter = new RgbaFrameConverter("camerax:front");

        long first = converter.convert(paddedImage(), 3, 2, PADDED_STRIDE, 0, 0L).sequence();
        long second = converter.convert(paddedImage(), 3, 2, PADDED_STRIDE, 0, 33L).sequence();

        assertEquals(first + 1, second);
    }

    /** RGBA of the 3x2 image; each pixel p is (p, p+1, p+2, 255), padding bytes are 99. */
    private static ByteBuffer paddedImage() {
        int[][] rows = {{A, B, C}, {D, E, F}};
        ByteBuffer buffer = ByteBuffer.allocate(rows.length * PADDED_STRIDE);
        for (int[] row : rows) {
            for (int p : row) {
                buffer.put(bytes(p, p + 1, p + 2, 255));
            }
            buffer.put(bytes(99, 99, 99, 99));
        }
        buffer.rewind();
        return buffer;
    }

    /** Packed RGB of the given pixels, in order. */
    private static byte[] rgb(int... pixels) {
        byte[] out = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            out[i * 3] = (byte) pixels[i];
            out[i * 3 + 1] = (byte) (pixels[i] + 1);
            out[i * 3 + 2] = (byte) (pixels[i] + 2);
        }
        return out;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
