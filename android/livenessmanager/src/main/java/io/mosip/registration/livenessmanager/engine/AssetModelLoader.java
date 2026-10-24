package io.mosip.registration.livenessmanager.engine;

import android.content.Context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.engine.EngineOptions;
import io.mosip.registration.liveness.engine.LivenessEngines;
import io.mosip.registration.liveness.model.ModelRegistry;

/**
 * Builds the real engine from the model bundle in the APK's {@code assets/models/} (B-07): reads
 * {@code models.json} and every file it lists, lets {@link ModelRegistry} check each SHA-256, then builds the
 * hybrid engine. Any problem is LIV-013; the engine is never built from a partial or altered bundle.
 */
public final class AssetModelLoader implements EngineHolder.Loader {

    /** The bundle's manifest, as named by liveness-core. */
    static final String MANIFEST = ModelRegistry.MANIFEST;

    private static final String DIR = "models/";

    /** Reads one asset by its path under {@code assets/}. */
    interface AssetReader {
        byte[] read(String assetPath) throws IOException;
    }

    private final AssetReader reader;

    AssetModelLoader(AssetReader reader) {
        this.reader = reader;
    }

    /**
     * @param context gives the APK's assets
     * @return a loader for the shipped bundle
     */
    public static AssetModelLoader fromAssets(Context context) {
        Context app = context.getApplicationContext();
        return new AssetModelLoader(path -> {
            try (InputStream in = app.getAssets().open(path)) {
                return readAll(in);
            }
        });
    }

    @Override
    public LivenessEngine load() throws LivenessException {
        String manifest;
        Map<String, byte[]> files = new HashMap<>();
        try {
            manifest = new String(reader.read(DIR + MANIFEST), StandardCharsets.UTF_8);
            JsonNode models = new ObjectMapper().readTree(manifest).path("models");
            if (!models.isArray() || models.size() == 0) {
                throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, "No models listed in " + MANIFEST);
            }
            for (JsonNode model : models) {
                String file = model.path("file").asText();
                files.put(file, reader.read(DIR + file));
            }
        } catch (IOException e) {
            throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, "Model bundle unreadable: " + e.getMessage());
        }
        ModelRegistry registry = ModelRegistry.load(files, manifest);
        return LivenessEngines.hybrid(registry, EngineOptions.defaults());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
