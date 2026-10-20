package docrag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResearchLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void workLeasePreventsShutdownDuringProcessingAndIdleShutdownOccursAfterRelease() throws Exception {
        var props = new ResearchClient.Options(directory, "fixture", 3, Research.Settings.defaults(), 1, Duration.ofSeconds(5), Duration.ofMillis(30), true);
        var stops = new CountDownLatch(1);
        var starts = new AtomicInteger();
        var control = new ResearchClient.ManagedResource() {
            public boolean isRunning() {
                return false;
            }

            public void start() {
                starts.incrementAndGet();
            }

            public void awaitReady() {
            }

            public void stop() {
                stops.countDown();
            }
        };
        var model = new ModelGateway() {
            public String embeddingIdentity() {
                return "fixture";
            }

            public float[] embed(String text) {
                return new float[]{1};
            }

            public Research.Generation generate(String s, String p, List<Research.Tool> t) {
                return new Research.Generation("{}", List.of());
            }
        };
        try (var session = new ResearchModelSession(model, control, props)) {
            var work = session.work();
            assertEquals(0, starts.get());
            session.embed("fixture");
            assertEquals(1, starts.get());
            assertFalse(stops.await(150, TimeUnit.MILLISECONDS));
            work.close();
            assertTrue(stops.await(2, TimeUnit.SECONDS));
            session.retryIdleShutdown(); // Synchronize with completion of marker cleanup after the stop callback.
            assertFalse(Files.exists(directory.resolve("vm-owned")));
        }
    }

    @Test
    void durableJobRecoveryMarksInterruptedAndNeverOverwritesTerminalResults() throws Exception {
        String id;
        try (var store = new ResearchJobStore(directory)) {
            var job = store.create("alice", new ResearchClient.Request("CHAT", null, List.of(), null, null, null, null, null, null, "conversation", "turn"));
            id = job.id();
            store.update(id, ResearchClient.State.PROCESSING, null, null);
        }
        try (var store = new ResearchJobStore(directory)) {
            assertEquals(ResearchClient.State.INTERRUPTED, store.get(id).state());
            assertEquals(1, store.recovered().size());
            store.update(id, ResearchClient.State.COMPLETED, null, null);
            assertEquals(ResearchClient.State.INTERRUPTED, store.get(id).state());
            assertThrows(Exception.class, () -> new ResearchJobStore(directory));
        }
    }

    @Test
    void readsExistingHostSnapshotWithoutLosingLegacyFields() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        Files.writeString(directory.resolve(id + ".json"), """
                {"id":"%s","owner":"system:telegram","request":{"kind":"NEWS","company":"ACME","periods":["UNASSIGNED"],
                 "question":null,"documentId":"telegram:42","file":null,"source":"https://example.invalid/report.pdf",
                 "newsId":42,"newsVersion":"2026-10-19T10:00:00"},"state":"PROCESSING","createdAt":"2026-10-19T10:00:00Z",
                 "updatedAt":"2026-10-19T10:00:00Z","answer":null,"error":null}
                """.formatted(id));
        try (var store = new ResearchJobStore(directory)) {
            var job = store.get(id);
            assertEquals(ResearchClient.State.INTERRUPTED, job.state());
            assertEquals(42L, job.request().newsId());
            assertEquals("telegram:42", job.request().documentId());
            assertEquals("2026-10-19T10:00:00", job.request().newsVersion());
            assertNull(job.request().conversationId());
        }
    }
}

