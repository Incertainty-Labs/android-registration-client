package io.mosip.registration.livenessmanager.engine;

import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * B-07: the shipped bundle is checked before the engine is built, and any problem refuses it (LIV-013, no
 * fallback). Building the engine itself needs ONNX Runtime's Android natives, so that path is checked on the
 * phone (androidTest).
 */
public class AssetModelLoaderTest {

    /** The module's real assets, read from the source tree. */
    private static final File MODELS = new File("src/main/assets/models");

    @Test
    public void tcEng_alteredModelFile_refusesTheBundle() {
        AssetModelLoader loader = new AssetModelLoader(name -> {
            byte[] bytes = read(name);
            if (name.endsWith("minifasnet_v2_2.7_80x80.onnx")) {
                bytes = Arrays.copyOf(bytes, bytes.length);
                bytes[100] ^= 0x01;
            }
            return bytes;
        });

        assertEngineError(loader);
    }

    @Test
    public void tcEng_missingModelFile_refusesTheBundle() {
        AssetModelLoader loader = new AssetModelLoader(name -> {
            if (name.endsWith("face_recognition_sface_2021dec.onnx")) {
                throw new FileNotFoundException(name);
            }
            return read(name);
        });

        assertEngineError(loader);
    }

    @Test
    public void tcEng_brokenManifest_refusesTheBundle() {
        AssetModelLoader loader = new AssetModelLoader(name ->
                name.endsWith(AssetModelLoader.MANIFEST) ? "{ not json".getBytes() : read(name));

        assertEngineError(loader);
    }

    @Test
    public void tcPerf_engineRunsWithThePhoneTimingsBestOptions() {
        // Release build on the A059 (8 cores): CPU with 2 threads and the detector on every 3rd frame was fastest;
        // XNNPACK was no faster, NNAPI 4-5 times slower
        assertEquals("CPU", AssetModelLoader.OPTIONS.requestedProvider());
        assertEquals(2, AssetModelLoader.OPTIONS.threads());
        assertEquals(3, AssetModelLoader.OPTIONS.detectEvery());
    }

    private static void assertEngineError(AssetModelLoader loader) {
        try {
            loader.load();
            fail("the bundle should be refused");
        } catch (LivenessException e) {
            assertEquals(LivenessErrorCode.ENGINE_ERROR, e.errorCode());
        }
    }

    private static byte[] read(String assetPath) throws IOException {
        return Files.readAllBytes(new File(MODELS.getParentFile(), assetPath).toPath());
    }
}
