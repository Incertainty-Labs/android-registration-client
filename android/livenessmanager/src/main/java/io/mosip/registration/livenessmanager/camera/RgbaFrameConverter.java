package io.mosip.registration.livenessmanager.camera;

import java.nio.ByteBuffer;

import io.mosip.registration.liveness.api.FaceFrame;

/**
 * Turns CameraX {@code RGBA_8888} analysis images into packed RGB {@link FaceFrame}s for one frame
 * source, numbering the frames in order. Not thread-safe; call it from the analysis thread only.
 */
public final class RgbaFrameConverter {

    private static final int RGBA_BYTES = 4;
    private static final int RGB_BYTES = 3;

    private final String sourceId;
    private long nextSequence;

    /**
     * @param sourceId id stamped on every frame, for example {@code "camerax:front"}
     */
    public RgbaFrameConverter(String sourceId) {
        this.sourceId = sourceId;
    }

    /**
     * Converts one image. The buffer is read with absolute gets, so its position is not changed.
     *
     * @param rgba            the single RGBA plane
     * @param width           image width in pixels
     * @param height          image height in pixels
     * @param rowStride       bytes per row in {@code rgba}, at least {@code width * 4}
     * @param rotationDegrees clockwise rotation that makes the image upright: 0, 90, 180 or 270
     * @param timestampMs     capture time in milliseconds
     * @return a new frame
     * @throws IllegalArgumentException if the rotation is not one of the four quarter turns, or the plane is too
     *                                  short for the given size and row stride
     */
    public FaceFrame convert(ByteBuffer rgba, int width, int height, int rowStride,
                             int rotationDegrees, long timestampMs) {
        if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180 or 270: " + rotationDegrees);
        }
        // The last row may come without its padding, so it only needs width * 4 bytes.
        long needed = (long) (height - 1) * rowStride + (long) width * RGBA_BYTES;
        if (rowStride < width * RGBA_BYTES || rgba.limit() < needed) {
            throw new IllegalArgumentException("RGBA plane too short for " + width + "x" + height
                    + " with row stride " + rowStride + ": " + rgba.limit() + " bytes");
        }
        boolean quarterTurn = rotationDegrees == 90 || rotationDegrees == 270;
        int outWidth = quarterTurn ? height : width;
        int outHeight = quarterTurn ? width : height;
        byte[] rgb = new byte[width * height * RGB_BYTES];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int outX;
                int outY;
                switch (rotationDegrees) {
                    case 90:
                        outX = height - 1 - y;
                        outY = x;
                        break;
                    case 180:
                        outX = width - 1 - x;
                        outY = height - 1 - y;
                        break;
                    case 270:
                        outX = y;
                        outY = width - 1 - x;
                        break;
                    default:
                        outX = x;
                        outY = y;
                }
                int in = y * rowStride + x * RGBA_BYTES;
                int out = (outY * outWidth + outX) * RGB_BYTES;
                rgb[out] = rgba.get(in);
                rgb[out + 1] = rgba.get(in + 1);
                rgb[out + 2] = rgba.get(in + 2);
            }
        }
        return new FaceFrame(outWidth, outHeight, rgb, timestampMs, nextSequence++, sourceId);
    }
}
