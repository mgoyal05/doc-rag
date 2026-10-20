package docrag.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import docrag.*;
import docrag.docling.DoclingExtractor;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Opt-in real-model evaluation. Does not conflate reference validity with factual groundedness.
 */
public final class EvaluationRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 7)
            throw new IllegalArgumentException("Usage: endpoint chatModel embeddingModel fixtureDirectory outputDirectory development|heldout|all timeoutSeconds");
        Path fixture = Path.of(args[3]).toAbsolutePath().normalize();
        Path output = Path.of(args[4]);
        Files.createDirectories(output);
        String split = args[5];
        if (!Set.of("development", "heldout", "all").contains(split))
            throw new IllegalArgumentException("Invalid evaluation split");
        var json = new ObjectMapper();
        List<String> rows = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        double recall = 0;
        int count = 0;
        int abstentionMatches = 0;
        int failures = 0;
        long started = System.nanoTime();
        long cpuStart = cpuNanos();
        var usages = new ArrayList<ResearchObserver.Usage>();
        var phaseNanos = new java.util.concurrent.ConcurrentHashMap<String, Long>();
        ResearchObserver observer = new ResearchObserver() {
            public void completed(String op, long nanos, int items, boolean success) {
                phaseNanos.merge(op, nanos, Long::sum);
            }

            public void usage(Usage usage) {
                usages.add(usage);
            }
        };
        var model = new OllamaGateway(args[0], args[1], args[2], Duration.ofSeconds(Long.parseLong(args[6])),
                new OllamaGateway.Profile(Integer.parseInt(System.getenv().getOrDefault("RESEARCH_EVAL_CONTEXT_TOKENS", "8192")), 2048, 4, false, ""), observer);
        var store = new PostgresResearchStore(DatabaseSettings.source(), DatabaseSettings.schema(), model.embeddingIdentity(), DatabaseSettings.dimensions());
        try (var engine = new ResearchEngine(Research.Settings.defaults(), model,
                new DoclingExtractor(DatabaseSettings.required("RESEARCH_DOCLING_URL"), Duration.ofMinutes(5), DatabaseSettings.required("RESEARCH_DOCLING_VERSION")), store)) {
            var chatbot = new ResearchChatbot(store, engine, model, new ToolRegistry(), companies -> Map.of());
            String uploadConversation = chatbot.createConversation("evaluation").id();
            Map<String, String> documentIds = new HashMap<>();
            Map<String, String> conversations = new HashMap<>();
            for (String line : Files.readAllLines(fixture.resolve("manifest.jsonl"))) {
                var doc = json.readTree(line);
                Path file = fixture.resolve(doc.path("file").asText()).normalize();
                if (!file.startsWith(fixture))
                    throw new IllegalArgumentException("Manifest file escapes fixture directory");
                var uploaded = chatbot.uploadDocument("evaluation", uploadConversation, file);
                if (uploaded.outcome() != ResearchChatbot.Outcome.DOCUMENT_READY)
                    throw new IllegalStateException("Fixture metadata unresolved: " + doc.path("id").asText());
                documentIds.put(doc.path("id").asText(), uploaded.documentId());
            }
            for (String line : Files.readAllLines(fixture.resolve("questions.jsonl"))) {
                var item = json.readTree(line);
                if (!split.equals("all") && !split.equals(item.path("split").asText())) continue;
                long before = System.nanoTime();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.path("id").asText());
                count++;
                try {
                    String company = item.path("company").asText();
                    String conversation = conversations.get(company);
                    if (conversation == null) {
                        conversation = chatbot.createConversation("evaluation").id();
                        conversations.put(company, conversation);
                    }
                    var result = chatbot.reply("evaluation", conversation, item.path("question").asText());
                    row.put("result", result);
                    var evidence = result.answer() == null ? List.<Research.Evidence>of() : result.answer().evidence();
                    Set<String> retrieved = new HashSet<>();
                    evidence.forEach(e -> retrieved.add(e.documentId()));
                    var expected = item.path("expectedDocuments");
                    int hits = 0;
                    for (var id : expected) if (retrieved.contains(documentIds.get(id.asText()))) hits++;
                    double sampleRecall = expected.isEmpty() ? (retrieved.isEmpty() ? 1 : 0) : (double) hits / expected.size();
                    recall += sampleRecall;
                    row.put("documentRecall", sampleRecall);
                    boolean match = (result.outcome() == ResearchChatbot.Outcome.INSUFFICIENT_EVIDENCE) == item.path("expectedAbstention").asBoolean();
                    if (match) abstentionMatches++;
                    row.put("abstentionMatchesExpected", match);
                    var resolved = result.scope() == null ? Set.<String>of() : new HashSet<>(result.scope().targets().stream().map(Research.Target::company).toList());
                    row.put("outOfResolvedScopeCitations", evidence.stream().filter(e -> !e.company().isEmpty() && !resolved.contains(e.company())).count());
                    row.put("humanSupportedAnswerJudgment", null);
                    row.put("humanNumericalCorrectnessJudgment", null);
                } catch (Exception e) {
                    failures++;
                    row.put("failureType", e.getClass().getSimpleName());
                }
                double millis = (System.nanoTime() - before) / 1_000_000.0;
                latencies.add(millis);
                row.put("latencyMs", millis);
                rows.add(json.writeValueAsString(row));
                Files.write(output.resolve("results.jsonl"), rows);
            }
        }
        Collections.sort(latencies);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("split", split);
        report.put("questions", count);
        report.put("failures", failures);
        report.put("meanAnswerContextDocumentRecall", count == 0 ? null : recall / count);
        report.put("abstentionMatchRate", count == 0 ? null : (double) abstentionMatches / count);
        report.put("latencyP50Ms", percentile(latencies, .5));
        report.put("latencyP95Ms", percentile(latencies, .95));
        report.put("wallMs", (System.nanoTime() - started) / 1_000_000.0);
        report.put("processCpuMs", (cpuNanos() - cpuStart) / 1_000_000.0);
        report.put("heapUsedAfterBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        report.put("supportedAnswerRate", null);
        report.put("numericalCorrectnessRate", null);
        report.put("phaseNanos", phaseNanos);
        report.put("modelUsage", usages);
        report.put("reviewStatus", "PENDING: model verification is not an independent human quality judgment");
        report.put("limitations", "Human judgments are required; local measurements do not establish server overlap safety or cloud inference costs.");
        Files.writeString(output.resolve("summary.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println(output.resolve("summary.json").toAbsolutePath());
    }

    private static long cpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
    }

    private static Double percentile(List<Double> values, double fraction) {
        return values.isEmpty() ? null : values.get(Math.max(0, (int) Math.ceil(values.size() * fraction) - 1));
    }
}
