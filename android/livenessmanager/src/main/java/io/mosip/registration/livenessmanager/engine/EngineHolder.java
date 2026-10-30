package io.mosip.registration.livenessmanager.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;

/**
 * The real liveness engine, built once per app in the background (B-07): loading the model bundle and the ONNX
 * sessions takes about a second, so it starts with the app and every check shares the result. A failure is
 * LIV-013 and fails closed; there is never a fallback to the stub. Thread-safe.
 */
public final class EngineHolder {

    /** Builds the engine; runs on the background executor. */
    public interface Loader {
        LivenessEngine load() throws LivenessException;
    }

    /** Told once the engine is ready or has failed; called on the loading thread, or at once if already known. */
    public interface Listener {
        void ready(LivenessEngine engine);

        void failed(LivenessException error);
    }

    private static final EngineHolder SHARED = new EngineHolder();

    private final List<Listener> waiting = new ArrayList<>();
    private LivenessEngine engine;
    private LivenessException failure;
    private boolean loading;

    /** @return the app's holder */
    public static EngineHolder shared() {
        return SHARED;
    }

    /**
     * Starts building the engine, unless it is built or being built. After a failure, it tries again.
     *
     * @param executor runs the loader
     * @param loader   builds the engine
     */
    public void start(Executor executor, Loader loader) {
        synchronized (this) {
            if (loading || engine != null) {
                return;
            }
            loading = true;
            failure = null;
        }
        executor.execute(() -> {
            LivenessEngine built = null;
            LivenessException error = null;
            try {
                built = loader.load();
            } catch (LivenessException e) {
                error = e;
            } catch (Throwable e) {
                // An Error too (no ONNX Runtime library, out of memory reading the models): it runs on the app's own
                // start-up thread, so it must neither crash the app nor leave the waiters waiting
                error = new LivenessException(LivenessErrorCode.ENGINE_ERROR, "Engine failed to load: " + e.getMessage());
            }
            finish(built, error);
        });
    }

    /** @param listener told when the engine is ready or has failed */
    public void whenReady(Listener listener) {
        LivenessEngine ready;
        LivenessException error;
        synchronized (this) {
            ready = engine;
            error = failure;
            if (ready == null && error == null) {
                waiting.add(listener);
                return;
            }
        }
        notify(listener, ready, error);
    }

    private void finish(LivenessEngine built, LivenessException error) {
        List<Listener> toNotify;
        synchronized (this) {
            loading = false;
            engine = built;
            failure = built == null ? error : null;
            toNotify = new ArrayList<>(waiting);
            waiting.clear();
        }
        for (Listener listener : toNotify) {
            notify(listener, built, error);
        }
    }

    private static void notify(Listener listener, LivenessEngine ready, LivenessException error) {
        if (ready != null) {
            listener.ready(ready);
        } else {
            listener.failed(error);
        }
    }
}
