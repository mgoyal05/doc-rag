package docrag.spring;

import docrag.ResearchClient;
import docrag.ResearchObserver;
import docrag.ResearchSchema;
import docrag.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real Spring property binding and real research factory against isolated PostgreSQL/pgvector.
 */
class ResearchSetupTest {
    @TempDir
    Path files;
    PGSimpleDataSource source;
    String schema;

    @BeforeEach
    void database() {
        String url = System.getenv("RESEARCH_TEST_JDBC_URL");
        assumeTrue(url != null, "Requires isolated PostgreSQL with pgvector >= 0.8");
        source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(System.getenv().getOrDefault("RESEARCH_TEST_DB_USER", "postgres"));
        source.setPassword(System.getenv().getOrDefault("RESEARCH_TEST_DB_PASSWORD", "postgres"));
        schema = "setup_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (source != null) sql("drop schema if exists " + schema + " cascade");
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(Beans.class).withBean(DataSource.class, () -> source)
                .withPropertyValues("research.directory=" + files.resolve("runtime"), "research.schema=" + schema,
                        "research.embedding-model=fixture", "research.embedding-dimensions=3",
                        "research.ollama-url=http://127.0.0.1:65530", "research.docling-url=http://127.0.0.1:65531");
    }

    @Test
    void freshDatabaseRequiresExplicitOptInAndReportsTheSetting() throws Exception {
        context().run(c -> {
            assertNotNull(c.getStartupFailure());
            assertTrue(c.getStartupFailure().getMessage().contains("research.initialize-schema=true"));
        });
        assertNull(value("select to_regclass('" + schema + ".profile')::text"));
    }

    @Test
    void optInUsesTheSuppliedDataSourceAndPreservesConversationsOnRestart() {
        var setup = context().withPropertyValues("research.initialize-schema=true");
        setup.run(c -> {
            assertNull(c.getStartupFailure());
            c.getBean(ResearchClient.class).createConversation("alice");
        });
        setup.run(c -> {
            assertNull(c.getStartupFailure());
            assertEquals(1, c.getBean(ResearchClient.class).listConversations("alice").size());
        });
    }

    @Test
    void incompatibleProfileIsRejectedWithoutAlteringStoredData() throws Exception {
        ResearchSchema.initialize(source, schema, 3, "original-model");
        sql("insert into " + schema + ".conversations(id,owner) values('" + UUID.randomUUID() + "','alice')");
        context().withPropertyValues("research.initialize-schema=true").run(c -> {
            assertNotNull(c.getStartupFailure());
            assertTrue(c.getStartupFailure().getMessage().contains("incompatible"));
        });
        assertEquals("original-model", value("select embedding_model from " + schema + ".profile"));
        assertEquals("1", value("select count(*)::text from " + schema + ".conversations"));
    }

    @Test
    void partialSchemaFailureRollsBackAndKeepsExistingRows() throws Exception {
        sql("create schema " + schema);
        sql("create table " + schema + ".documents(marker text)");
        sql("insert into " + schema + ".documents values('preserve')");
        context().withPropertyValues("research.initialize-schema=true").run(c -> assertNotNull(c.getStartupFailure()));
        assertNull(value("select to_regclass('" + schema + ".profile')::text"));
        assertEquals("preserve", value("select marker from " + schema + ".documents"));
    }

    private void sql(String sql) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private String value(String sql) throws Exception {
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    static class Beans {
        @Bean
        @ConfigurationProperties("research")
        ResearchProperties researchProperties() {
            return new ResearchProperties();
        }

        @Bean(destroyMethod = "close")
        ResearchClient researchClient(ResearchProperties properties, DataSource source) throws Exception {
            return ResearchSpring.create(properties, source, new ToolRegistry(), names -> Map.of(),
                    new ResearchClient.ManagedResource() {
                    }, new ResearchClient.Enrichment() {
                    }, ResearchObserver.NONE);
        }
    }
}
