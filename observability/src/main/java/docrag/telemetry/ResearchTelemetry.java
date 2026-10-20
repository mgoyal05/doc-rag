package docrag.telemetry;

import docrag.ResearchObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.StatusCode;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional adapter: Micrometer owns metrics; OpenTelemetry owns spans. No text/content capture.
 */
public final class ResearchTelemetry implements ResearchObserver {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ResearchTelemetry.class);
    private static final Set<String> OPERATIONS = Set.of("job", "queue.wait", "extraction", "indexing", "ingest", "ask", "summarize", "retrieval",
            "embedding", "generation", "verification", "document_metadata", "query_scope", "validation", "admission", "tool", "lifecycle", "conversation");
    private static final Set<String> OUTCOMES = Set.of("OK", "ERROR", "ACCEPTED", "ANSWERED", "INSUFFICIENT_EVIDENCE", "CLARIFICATION_REQUIRED", "DOCUMENT_READY",
            "FAILED", "CANCELLED", "INTERRUPTED", "RESOURCE_LIMIT", "INVALID_INPUT", "INVALID_RESPONSE", "EXTRACTION_FAILED", "UNSUPPORTED_CLAIM", "NUMERICAL_VALIDATION",
            "METADATA_AMBIGUOUS", "VERIFIER_UNAVAILABLE", "MODEL_UNAVAILABLE", "STARTING_MODEL", "SHUTDOWN_FAILED");
    private final MeterRegistry metrics;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public ResearchTelemetry(MeterRegistry metrics, OpenTelemetry telemetry) {
        this.metrics = Objects.requireNonNull(metrics);
        this.tracer = Objects.requireNonNull(telemetry).getTracer("docrag", "0.1.0");
    }

    private static String operation(String value) {
        return OPERATIONS.contains(value) ? value : "other";
    }

    private static String outcome(String value) {
        return OUTCOMES.contains(value) ? value : "ERROR";
    }

    @Override
    public void completed(String op, long nanos, int items, boolean success) {
        metrics.timer("research.operation", "operation", operation(op), "success", Boolean.toString(success)).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
        metrics.counter("research.items", "operation", operation(op)).increment(Math.max(0, items));
    }

    @Override
    public void event(String op, String result, int count) {
        metrics.counter("research.outcome", "operation", operation(op), "outcome", outcome(result)).increment(Math.max(0, count));
        io.opentelemetry.api.trace.Span.current().addEvent("research.outcome", io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("research.operation"), operation(op),
                io.opentelemetry.api.common.AttributeKey.stringKey("research.outcome"), outcome(result)));
    }

    @Override
    public void gauge(String name, long value) {
        if (!Set.of("active", "queue.depth").contains(name)) return;
        gauges.computeIfAbsent(name, key -> {
            var number = new AtomicLong();
            io.micrometer.core.instrument.Gauge.builder("research." + key, number, AtomicLong::get).register(metrics);
            return number;
        }).set(Math.max(0, value));
    }

    @Override
    public ResearchObserver.Context capture() {
        var captured = io.opentelemetry.context.Context.current();
        return () -> captured.makeCurrent();
    }

    @Override
    public Span start(String op, String jobId) {
        String operation = operation(op);
        long started = System.nanoTime();
        var span = tracer.spanBuilder("research." + operation).startSpan();
        if (jobId != null && jobId.matches("[a-f0-9-]{36}")) span.setAttribute("research.job.id", jobId);
        var scope = span.makeCurrent();
        return new Span() {
            private boolean ended;

            public void finish(String result, int items) {
                if (ended) return;
                ended = true;
                try {
                    span.setAttribute("research.outcome", outcome(result));
                    if (!"OK".equals(result)) span.setStatus(StatusCode.ERROR);
                    completed(operation, System.nanoTime() - started, items, "OK".equals(result));
                    event(operation, result, 1);
                    LOG.atInfo().addKeyValue("research_operation", operation).addKeyValue("research_outcome", outcome(result))
                            .addKeyValue("trace_id", span.getSpanContext().getTraceId()).addKeyValue("span_id", span.getSpanContext().getSpanId())
                            .log("research.operation");
                } finally {
                    scope.close();
                    span.end();
                }
            }
        };
    }

    @Override
    public void usage(Usage usage) {
        String role = Set.of("generation", "verification", "document_metadata", "query_scope", "embedding").contains(usage.role()) ? usage.role() : "other";
        // Model names, including names returned by an untrusted endpoint, are deliberately not metric dimensions.
        if (usage.inputTokens() != null)
            metrics.counter("research.model.tokens", "role", role, "direction", "input").increment(usage.inputTokens());
        if (usage.outputTokens() != null)
            metrics.counter("research.model.tokens", "role", role, "direction", "output").increment(usage.outputTokens());
        duration("load", role, usage.loadNanos());
        duration("prompt", role, usage.promptNanos());
        duration("generation", role, usage.generationNanos());
    }

    private void duration(String stage, String role, Long nanos) {
        if (nanos != null)
            metrics.timer("research.model.duration", "stage", stage, "role", role).record(nanos, TimeUnit.NANOSECONDS);
    }
}
