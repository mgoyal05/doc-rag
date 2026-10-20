package example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.SpringApplication;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocRagApplicationTest {
    @TempDir
    Path files;

    @Test
    void freshSchemaStartsTheRealServerAndServesAuthenticatedChatResources() throws Exception {
        String url = System.getenv("RESEARCH_TEST_JDBC_URL");
        assumeTrue(url != null, "Requires isolated PostgreSQL/pgvector");
        String schema = "demo_test_" + UUID.randomUUID().toString().replace("-", "");
        var source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(System.getenv().getOrDefault("RESEARCH_TEST_DB_USER", "postgres"));
        source.setPassword(System.getenv().getOrDefault("RESEARCH_TEST_DB_PASSWORD", "postgres"));
        var app = new SpringApplication(DocRagApplication.class);
        app.setDefaultProperties(Map.of("RESEARCH_JDBC_URL", url, "RESEARCH_DB_USER", source.getUser(),
                "RESEARCH_DB_PASSWORD", source.getPassword(), "DEMO_PASSWORD", "isolated-test-password"));
        try (var context = app.run("--server.port=0", "--research.initialize-schema=true", "--research.schema=" + schema,
                "--research.directory=" + files, "--research.embedding-model=fixture", "--research.embedding-dimensions=3",
                "--research.ollama-url=http://127.0.0.1:65530", "--research.docling-url=http://127.0.0.1:65531",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            var http = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build();
            String basic = "Basic " + Base64.getEncoder().encodeToString("demo:isolated-test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var page = get(http, base + "/", basic);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("/js/research-chat-loader.js"));
            assertEquals(200, get(http, base + "/js/research-chat-loader.js", basic).statusCode());
            assertEquals(200, get(http, base + "/document-research/chat-widget.js", basic).statusCode());
            var config = get(http, base + "/api/research/chat/config", basic);
            assertEquals(200, config.statusCode());
            assertTrue(config.body().contains("csrfToken"));
            var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/"))
                    .header("Accept", "text/html").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, anonymous.statusCode());
        } finally {
            try (var c = source.getConnection(); var s = c.createStatement()) {
                s.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    private HttpResponse<String> get(HttpClient http, String url, String basic) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Authorization", basic).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
