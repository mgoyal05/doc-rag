package docrag.telemetry;

import docrag.ResearchObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResearchTelemetryTest {
    @Test
    void parentContextCrossesWorkerBoundaryAndContentCannotBecomeLabels() throws Exception {
        var exported = InMemorySpanExporter.create();
        var meters = new SimpleMeterRegistry();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build()) {
            var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var observer = new ResearchTelemetry(meters, telemetry);
            var parent = telemetry.getTracer("test").spanBuilder("http.research").startSpan();
            var worker = Executors.newSingleThreadExecutor();
            try (var current = parent.makeCurrent()) {
                var captured = observer.capture();
                worker.submit(() -> {
                    try (var attached = captured.attach(); var job = observer.start("job", "11111111-1111-1111-1111-111111111111")) {
                        try (var generation = observer.start("generation", null)) {
                            generation.finish("OK", 1);
                        }
                        job.finish("OK", 1);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                }).get(3, TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
                parent.end();
            }
            var spans = exported.getFinishedSpanItems();
            assertEquals(3, spans.size());
            var job = spans.stream().filter(s -> s.getName().equals("research.job")).findFirst().orElseThrow();
            assertEquals(parent.getSpanContext().getSpanId(), job.getParentSpanId());
            assertTrue(spans.stream().allMatch(s -> s.getTraceId().equals(parent.getSpanContext().getTraceId())));
            observer.event("private PDF contents", "secret user", 1);
            observer.usage(new ResearchObserver.Usage("generation", "sensitive-model-response", 10L, 5L, null, null, null));
            String labels = meters.getMeters().stream().map(m -> m.getId().toString()).reduce("", String::concat);
            assertFalse(labels.contains("private"));
            assertFalse(labels.contains("secret"));
            assertFalse(labels.contains("sensitive-model-response"));
            assertFalse(labels.contains("11111111"));
            assertEquals(10, meters.find("research.model.tokens").tag("direction", "input").counter().count());
        } finally {
            meters.close();
        }
    }

    @Test
    void brokenObserverCannotFailProcessingAndOldLambdaRemainsUsable() {
        ResearchObserver broken = (o, n, c, s) -> {
            throw new IllegalStateException("sensitive exporter exception");
        };
        var safe = ResearchObserver.safe(broken);
        assertDoesNotThrow(() -> {
            try (var scope = safe.start("generation", null)) {
                scope.finish("OK", 1);
            }
            safe.event("job", "OK", 1);
            safe.capture().attach().close();
        });
    }

    @Test
    void failedSdkExporterDoesNotFailOperationAndUsesBoundedBatching() {
        var exporter = new io.opentelemetry.sdk.trace.export.SpanExporter() {
            public io.opentelemetry.sdk.common.CompletableResultCode export(java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofFailure();
            }

            public io.opentelemetry.sdk.common.CompletableResultCode flush() {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }

            public io.opentelemetry.sdk.common.CompletableResultCode shutdown() {
                return io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess();
            }
        };
        var processor = io.opentelemetry.sdk.trace.export.BatchSpanProcessor.builder(exporter).setMaxQueueSize(8).setMaxExportBatchSize(4)
                .setExporterTimeout(java.time.Duration.ofMillis(200)).setScheduleDelay(java.time.Duration.ofMillis(10)).build();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            var meters = new SimpleMeterRegistry();
            var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var observer = ResearchObserver.safe(new ResearchTelemetry(meters, telemetry));
            assertDoesNotThrow(() -> {
                try (var job = observer.start("job", null)) {
                    job.finish("OK", 1);
                }
            });
            provider.forceFlush().join(1, TimeUnit.SECONDS);
            assertEquals(1, meters.get("research.operation").tag("operation", "job").timer().count());
            meters.close();
        }
    }
}
