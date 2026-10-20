package docrag.example;

import docrag.*;
import docrag.docling.DoclingExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in: real facade, SDKs and PostgreSQL run against the checked-in local Postman HTTP simulation.
 */
class SimulatedClientTest {
    @TempDir
    Path files;

    private static ResearchClient.Job await(ResearchClient client, String id) throws Exception {
        return client.await(id, "alice", Duration.ofSeconds(20));
    }

    @Test
    void completeFacadePipelineAndModelOutageUseRealHttpTransports() throws Exception {
        String base = System.getenv("RESEARCH_SIMULATION_URL"), url = System.getenv("RESEARCH_TEST_JDBC_URL");
        assumeTrue(base != null && url != null, "Requires local Postman simulation and isolated pgvector database");
        var source = new com.zaxxer.hikari.HikariDataSource();
        source.setJdbcUrl(url);
        source.setMaximumPoolSize(Integer.parseInt(System.getenv().getOrDefault("RESEARCH_TEST_POOL_SIZE", "4")));
        source.setMinimumIdle(1);
        source.setUsername(System.getenv().getOrDefault("RESEARCH_TEST_DB_USER", "postgres"));
        source.setPassword(System.getenv().getOrDefault("RESEARCH_TEST_DB_PASSWORD", "postgres"));
        String schema = "sim_test_" + UUID.randomUUID().toString().replace("-", "");
        ResearchSchema.initialize(source, schema, 3, "test-model");
        var options = new ResearchClient.Options(files.resolve("runtime"), schema, 3, Research.Settings.defaults(), 1, Duration.ofSeconds(15), Duration.ofMillis(20), false);
        var extractor = new DoclingExtractor(base + "/docling", Duration.ofSeconds(5), "synthetic-contract");
        String conversation;
        try {
            try (var client = ResearchClient.builder(source, new OllamaGateway(base + "/ollama", "test-chat", "test-model", Duration.ofSeconds(5)), extractor).options(options).build()) {
                conversation = client.createConversation("alice").id();
                var pdf = Files.writeString(files.resolve("report.pdf"), "%PDF-synthetic-contract");
                var upload = client.submitUpload("alice", conversation, pdf, "report.pdf");
                assertEquals(ResearchClient.State.COMPLETED, await(client, upload.id()).state());
                assertEquals(ResearchChatbot.Outcome.DOCUMENT_READY, client.history("alice", conversation).getLast().result().outcome());
                var job = client.submitMessage("alice", conversation, "What is Acme's Q1 2025 revenue?");
                var finished = await(client, job.id());
                assertEquals(ResearchClient.State.COMPLETED, finished.state(), finished.error());
                var result = client.history("alice", conversation).getLast().result();
                assertEquals(ResearchChatbot.Outcome.ANSWERED, result.outcome());
                assertEquals(java.util.List.of(1, 2), result.answer().evidence().getFirst().pages());
                assertTrue(result.answer().claims().getFirst().text().contains("125"));
                assertTrue(result.answer().validation().accepted());
                for (String question : java.util.List.of("Has ACME acquired a competitor?", "Explain literal-quantity")) {
                    var rejected = client.submitMessage("alice", conversation, question);
                    assertEquals(ResearchClient.State.FAILED, await(client, rejected.id()).state());
                    var withheld = client.history("alice", conversation).getLast().result();
                    assertNull(withheld.answer());
                    assertEquals(question.contains("acquired") ? "UNSUPPORTED_CLAIM" : "NUMERICAL_VALIDATION", withheld.failureCode());
                }
            }
            for (String verifier : java.util.List.of("malformed-verifier", "slow-verifier")) {
                var guarded = new OllamaGateway(base + "/ollama", "test-chat", "test-model", Duration.ofMillis(500), new OllamaGateway.Profile(16384, 2048, 2, false, verifier), ResearchObserver.NONE);
                try (var client = ResearchClient.builder(source, guarded, extractor).options(options).build()) {
                    var rejected = client.submitMessage("alice", conversation, "What is revenue?");
                    assertEquals(ResearchClient.State.FAILED, await(client, rejected.id()).state());
                    var withheld = client.history("alice", conversation).getLast().result();
                    assertNull(withheld.answer());
                    assertEquals(verifier.startsWith("slow") ? "VERIFIER_UNAVAILABLE" : "INVALID_RESPONSE", withheld.failureCode());
                }
            }
            try (var client = ResearchClient.builder(source, new OllamaGateway(base + "/ollama-failure", "test-chat", "test-model", Duration.ofSeconds(5)), extractor).options(options).build()) {
                try (var http = java.net.http.HttpClient.newHttpClient()) {
                    var probe = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/ollama-failure/api/chat")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                    assertEquals(503, probe.statusCode(), "The simulation must inject the intended upstream failure");
                }
                var failed = client.submitMessage("alice", conversation, "Revenue?");
                assertEquals(ResearchClient.State.FAILED, await(client, failed.id()).state());
                assertEquals(ResearchChatbot.Outcome.FAILED, client.history("alice", conversation).getLast().result().outcome());
                assertNull(client.conversation("alice", conversation).activeTurn());
            }
        } finally {
            try {
                try (var c = source.getConnection(); var s = c.createStatement()) {
                    s.execute("drop schema " + schema + " cascade");
                }
            } finally {
                source.close();
            }
        }
    }
}
