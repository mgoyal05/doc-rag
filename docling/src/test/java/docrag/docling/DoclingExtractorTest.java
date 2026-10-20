package docrag.docling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoclingExtractorTest {
    @TempDir
    Path files;
    HttpServer server;
    String response, request, path;

    static Map<String, Object> chunk(int index, List<Integer> pages) {
        return Map.of("chunk_index", index, "text", "Results\n| Quarter | Revenue |\n| Q1 | 100 |",
                "raw_text", "| Quarter | Revenue |\n| Q1 | 100 |", "num_tokens", 32, "headings", List.of("Results"), "page_numbers", pages);
    }

    static String fixture(String status, List<Map<String, Object>> chunks) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("chunks", chunks, "documents", List.of(Map.of("status", status, "errors", List.of(), "content", Map.of("json_content",
                Map.of("schema_name", "DoclingDocument", "version", "1.7.0", "name", "fixture", "pages", Map.of("1", Map.of("page_no", 1, "size", Map.of("width", 612, "height", 792)), "2", Map.of("page_no", 2, "size", Map.of("width", 612, "height", 792)))))))));
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().getPath();
            request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        response = fixture("success", List.of(chunk(1, List.of(2)), chunk(0, List.of(1, 2))));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    DoclingExtractor extractor() {
        return new DoclingExtractor("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(3), "fixture-backend", "google/embeddinggemma-300m", 512);
    }

    Path file() throws Exception {
        return Files.writeString(files.resolve("report.pdf"), "%PDF-fixture");
    }

    @Test
    void usesNativeHybridApiAndRetainsRawContextAndMultiPageProvenance() throws Exception {
        var chunks = extractor().chunks(file(), 2, 10);
        assertEquals(2, chunks.size());
        assertEquals(List.of(1, 2), chunks.getFirst().pages());
        assertEquals("| Quarter | Revenue |\n| Q1 | 100 |", chunks.getFirst().text());
        assertTrue(chunks.getFirst().embeddingText().startsWith("Results"));
        assertEquals(List.of("Results"), chunks.getFirst().headings());
        assertEquals("/v1/chunk/hybrid/source", path);
        var json = new ObjectMapper().readTree(request);
        assertEquals(512, json.path("chunking_options").path("max_tokens").asInt());
        assertTrue(json.path("chunking_options").path("merge_peers").asBoolean());
        assertTrue(json.path("chunking_options").path("include_raw_text").asBoolean());
        assertEquals("google/embeddinggemma-300m", json.path("chunking_options").path("tokenizer").asText());
    }

    @Test
    void rejectsPartialExtractionMissingPagesAndOversizedChunks() throws Exception {
        response = fixture("partial_success", List.of(chunk(0, List.of(1, 2))));
        assertThrows(java.io.IOException.class, () -> extractor().chunks(file(), 2, 10));
        response = fixture("success", List.of(chunk(0, List.of(1))));
        assertThrows(java.io.IOException.class, () -> extractor().chunks(file(), 2, 10));
        var large = new LinkedHashMap<>(chunk(0, List.of(1, 2)));
        large.put("num_tokens", 513);
        response = fixture("success", List.of(large));
        assertThrows(java.io.IOException.class, () -> extractor().chunks(file(), 2, 10));
    }
}
