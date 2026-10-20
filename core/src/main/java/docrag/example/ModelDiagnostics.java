package docrag.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import docrag.OllamaGateway;
import docrag.Research;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Explicit, opt-in model compatibility probe. Never invoked at library/application startup.
 */
public final class ModelDiagnostics {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: endpoint chatModel embeddingModel");
        long started = System.nanoTime();
        var usage = new java.util.ArrayList<docrag.ResearchObserver.Usage>();
        var observer = new docrag.ResearchObserver() {
            public void completed(String op, long n, int count, boolean ok) {
            }

            public void usage(Usage value) {
                usage.add(value);
            }
        };
        var model = new OllamaGateway(args[0], args[1], args[2], Duration.ofMinutes(3), new OllamaGateway.Profile(8192, 2048, 4, false, ""), observer);
        float[] vector = model.embed("Document research compatibility probe.");
        if (vector.length == 0 || vector.length > 1024)
            throw new IllegalStateException("Embedded index supports dimensions 1-1024; select a compatible embedding model");
        var probe = new Research.Tool() {
            public String name() {
                return "read_probe";
            }

            public String description() {
                return "Read a fixed compatibility probe value. Call this tool once before replying.";
            }

            public List<String> arguments() {
                return List.of();
            }

            public Research.ToolResult execute(Map<String, String> arguments) {
                return new Research.ToolResult("Probe value: ready", "synthetic compatibility probe");
            }
        };
        var generation = model.generate("Call read_probe, then return JSON only: {\"claims\":[]}",
                "Verify tool calling by reading the probe value.", List.of(probe));
        if (generation.tools().isEmpty() || !new ObjectMapper().readTree(generation.json()).path("claims").isArray())
            throw new IllegalStateException("Chat model did not demonstrate native tool calling and JSON output; choose/configure a compatible model");
        var report = new java.util.LinkedHashMap<String, Object>();
        report.put("chatModel", args[1]);
        report.put("embeddingModel", args[2]);
        report.put("embeddingDimensions", vector.length);
        report.put("modelUsage", usage);
        report.put("probeWallMs", (System.nanoTime() - started) / 1_000_000.0);
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("architecture", System.getProperty("os.arch"));
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            report.put("hostTotalMemoryBytes", os.getTotalMemorySize());
            report.put("hostFreeMemoryBytes", os.getFreeMemorySize());
        }
        long generated = usage.stream().filter(u -> u.outputTokens() != null).mapToLong(u -> u.outputTokens()).sum();
        long nanos = usage.stream().filter(u -> u.generationNanos() != null).mapToLong(u -> u.generationNanos()).sum();
        report.put("generationTokensPerSecond", nanos == 0 ? null : generated * 1_000_000_000.0 / nanos);
        report.put("modelDetails", metadata(args[0], "/api/show", Map.of("model", args[1]), List.of("capabilities", "details", "model_info")));
        report.put("installedDigests", metadata(args[0], "/api/tags", null, List.of("models")));
        report.put("runtimeMemory", metadata(args[0], "/api/ps", null, List.of("models")));
        report.put("limitations", "Runtime size is provider-reported, not peak process RSS. Missing metadata is UNAVAILABLE. This is not a quality or shared-workload benchmark.");
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("PASS: endpoint access, embedding dimension=" + vector.length + ", native read-only tool call, and JSON claims array.");
        System.out.println("This probe does not measure factual accuracy, throughput or document extraction quality.");
    }

    private static Object metadata(String endpoint, String path, Map<String, String> body, List<String> fields) {
        var json = new ObjectMapper();
        try (var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint.replaceAll("/$", "") + path)).timeout(Duration.ofSeconds(5));
            if (body != null)
                request.header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            else request.GET();
            var response = http.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() != 200) return "UNAVAILABLE";
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) return "UNAVAILABLE";
                var tree = json.readTree(bytes);
                var result = new java.util.LinkedHashMap<String, Object>();
                for (String field : fields) if (tree.has(field)) result.put(field, tree.get(field));
                return result;
            }
        } catch (Exception failure) {
            return "UNAVAILABLE";
        }
    }
}
