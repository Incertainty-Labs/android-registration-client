package io.mosip.registration.livenessmanager.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import io.mosip.registration.liveness.api.LivenessEngine;
import io.mosip.registration.liveness.api.LivenessErrorCode;
import io.mosip.registration.liveness.api.LivenessException;
import io.mosip.registration.liveness.engine.LivenessEngines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** B-07: the real engine is built once per app, in the background, and fails closed (LIV-013). */
public class EngineHolderTest {

    private final List<Runnable> queued = new ArrayList<>();
    private final Executor background = queued::add;
    private final LivenessEngine engine = LivenessEngines.stub(Collections.emptyMap());

    @Test
    public void tcEng_engine_isBuiltOnceAndSharedByEveryWaiter() {
        EngineHolder holder = new EngineHolder();
        int[] loads = {0};
        holder.start(background, () -> {
            loads[0]++;
            return engine;
        });
        holder.start(background, () -> {
            loads[0]++;
            return engine;
        });
        Recorder first = new Recorder();
        holder.whenReady(first);

        runQueued();
        Recorder later = new Recorder();
        holder.whenReady(later);

        assertEquals(1, loads[0]);
        assertSame(engine, first.engine);
        assertSame(engine, later.engine);
    }

    @Test
    public void tcEng_loadFailure_isReportedAsLiv013() {
        EngineHolder holder = new EngineHolder();
        holder.start(background, () -> {
            throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, "model hash mismatch");
        });
        Recorder waiter = new Recorder();
        holder.whenReady(waiter);

        runQueued();

        assertNull(waiter.engine);
        assertEquals(LivenessErrorCode.ENGINE_ERROR, waiter.error.errorCode());
    }

    @Test
    public void tcEng_unexpectedFailure_isAlsoLiv013() {
        EngineHolder holder = new EngineHolder();
        holder.start(background, () -> {
            throw new IllegalStateException("no ONNX runtime");
        });
        Recorder waiter = new Recorder();
        holder.whenReady(waiter);

        runQueued();

        assertEquals(LivenessErrorCode.ENGINE_ERROR, waiter.error.errorCode());
        assertTrue(waiter.error.getMessage().contains("no ONNX runtime"));
    }

    @Test
    public void tcEng_startAfterAFailure_triesAgain() {
        EngineHolder holder = new EngineHolder();
        holder.start(background, () -> {
            throw new LivenessException(LivenessErrorCode.ENGINE_ERROR, "first");
        });
        runQueued();

        holder.start(background, () -> engine);
        runQueued();
        Recorder waiter = new Recorder();
        holder.whenReady(waiter);

        assertSame(engine, waiter.engine);
    }

    @Test
    public void tcEng_waitingBeforeStart_isAnsweredOnceItLoads() {
        EngineHolder holder = new EngineHolder();
        Recorder early = new Recorder();
        holder.whenReady(early);

        holder.start(background, () -> engine);
        runQueued();

        assertSame(engine, early.engine);
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.remove(0).run();
        }
    }

    private static final class Recorder implements EngineHolder.Listener {
        private LivenessEngine engine;
        private LivenessException error;

        @Override
        public void ready(LivenessEngine value) {
            engine = value;
        }

        @Override
        public void failed(LivenessException value) {
            error = value;
        }
    }
}
