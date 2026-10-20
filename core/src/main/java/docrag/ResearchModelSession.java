package docrag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Coordinates every embedding/generation call; ownership is durable across application restarts.
 */
final class ResearchModelSession implements ModelGateway, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ResearchModelSession.class);
    private final ModelGateway delegate;
    private final ResearchClient.ManagedResource control;
    private final ResearchClient.Options properties;
    private final ResearchObserver observer;
    private final Path marker;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "research-vm-idle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopFailed = new AtomicBoolean();
    private final ThreadLocal<Consumer<Boolean>> progress = new ThreadLocal<>();
    private final String vmIdentity;
    private ScheduledFuture<?> shutdown;
    private boolean ready;
    private boolean owned;
    private int active;
    private int work;
    private boolean closed;

    public ResearchModelSession(ModelGateway delegate, ResearchClient.ManagedResource control, ResearchClient.Options properties) throws Exception {
        this(delegate, control, properties, ResearchObserver.NONE);
    }

    public ResearchModelSession(ModelGateway delegate, ResearchClient.ManagedResource control, ResearchClient.Options properties, ResearchObserver observer) throws Exception {
        this.delegate = delegate;
        this.control = control;
        this.properties = properties;
        this.observer = ResearchObserver.safe(observer);
        if (properties.idleGrace().isNegative())
            throw new IllegalArgumentException("Idle grace must not be negative");
        marker = properties.directory().resolve("vm-owned");
        vmIdentity = control.identity();
        owned = properties.manageResource() && Files.exists(marker);
        if (owned && !Files.readString(marker).trim().equals(vmIdentity))
            throw new IllegalStateException("Owned VM marker belongs to a different configured VM; reconcile ownership explicitly");
        if (owned) scheduleStop();
    }

    public String embeddingIdentity() {
        return delegate.embeddingIdentity();
    }

    public AutoCloseable progress(Consumer<Boolean> callback) {
        progress.set(callback);
        return progress::remove;
    }

    /**
     * Pins an already-owned VM across extraction, indexing and model phases without starting it early.
     */
    public synchronized AutoCloseable work() {
        if (closed) throw new IllegalStateException("Model session is closed");
        if (shutdown != null) shutdown.cancel(false);
        work++;
        var released = new AtomicBoolean();
        return () -> {
            synchronized (ResearchModelSession.this) {
                if (released.compareAndSet(false, true)) {
                    work--;
                    idleAfterRelease();
                }
            }
        };
    }

    public float[] embed(String text) throws Exception {
        acquire();
        try {
            return delegate.embed(text);
        } finally {
            release();
        }
    }

    public Research.Generation generate(String system, String prompt, List<Research.Tool> tools) throws Exception {
        acquire();
        try {
            return delegate.generate(system, prompt, tools);
        } finally {
            release();
        }
    }

    public String structured(Structure kind, String system, String prompt) throws Exception {
        acquire();
        try {
            return delegate.structured(kind, system, prompt);
        } finally {
            release();
        }
    }

    private synchronized void acquire() throws Exception {
        if (closed) throw new IllegalStateException("Model session is closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Model work cancelled");
        if (shutdown != null) shutdown.cancel(false);
        if (properties.manageResource() && !ready) {
            if (progress.get() != null) progress.get().accept(true);
            Files.createDirectories(marker.getParent());
            try (var span = observer.start("lifecycle", null)) {
                observer.event("lifecycle", "STARTING_MODEL", 1);
                if (!control.isRunning()) {
                    // State was verified stopped. Persist ownership before starting, including crashes during the operation.
                    Files.writeString(marker, vmIdentity + "\n");
                    owned = true;
                    control.start();
                }
                control.awaitReady();
                ready = true;
                span.finish("OK", 1);
            } catch (Exception e) {
                if (owned) scheduleStop();
                throw e;
            }
        }
        if (progress.get() != null) progress.get().accept(false);
        active++;
    }

    private synchronized void release() {
        active--;
        idleAfterRelease();
    }

    private void idleAfterRelease() {
        if (active == 0 && work == 0 && owned) {
            if (closed) stopIdle();
            else scheduleStop();
        }
    }

    private synchronized void scheduleStop() {
        if (shutdown != null) shutdown.cancel(false);
        shutdown = timer.schedule(this::stopIdle, properties.idleGrace().toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void stopIdle() {
        if (active != 0 || work != 0 || !owned) return;
        try {
            control.stop();
            Files.deleteIfExists(marker);
            owned = false;
            ready = false;
            stopFailed.set(false);
            observer.event("lifecycle", "OK", 1);
        } catch (Exception e) {
            stopFailed.set(true);
            observer.event("lifecycle", "SHUTDOWN_FAILED", 1);
            log.error("Research VM shutdown failed; ownership retained for explicit retry/restart ({})", e.getClass().getSimpleName());
        }
    }

    public boolean shutdownFailed() {
        return stopFailed.get();
    }

    public synchronized void retryIdleShutdown() {
        if (active == 0 && work == 0 && owned) scheduleStop();
    }

    public synchronized void close() {
        closed = true;
        if (shutdown != null) shutdown.cancel(false);
        if (active == 0) stopIdle();
        timer.shutdown();
    }

}

