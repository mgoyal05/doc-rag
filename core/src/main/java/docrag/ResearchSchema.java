package docrag;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Explicit, additive provisioning. Spring startup invokes it only with an operator's setup opt-in.
 */
public final class ResearchSchema {
    private ResearchSchema() {
    }

    static String identifier(String schema) {
        if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,47}"))
            throw new IllegalArgumentException("Invalid research schema");
        return schema;
    }

    /**
     * Explicit opt-in setup: create an absent profile, or validate and preserve an existing one.
     */
    public static void initializeIfMissing(DataSource source, String schema, int dimensions, String model) throws Exception {
        identifier(schema);
        boolean exists;
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("select to_regclass(?)")) {
            statement.setString(1, schema + ".profile");
            try (var row = statement.executeQuery()) {
                row.next();
                exists = row.getString(1) != null;
            }
        }
        if (exists) new PostgresResearchStore(source, schema, model, dimensions);
        else initialize(source, schema, dimensions, model);
    }

    public static void initialize(DataSource source, String schema, int dimensions, String model) throws Exception {
        identifier(schema);
        Research.required(model, "embedding model");
        if (dimensions < 1 || dimensions > 2000) throw new IllegalArgumentException("Supported dimensions: 1-2000");
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement(); var result = statement.executeQuery(
                        "select extversion from pg_extension where extname='vector'")) {
                    if (!result.next() || !supported(result.getString(1)))
                        throw new SQLException("Install pgvector >= 0.8 before setup");
                }
                try (var statement = connection.prepareStatement("select to_regclass(?)")) {
                    statement.setString(1, schema + ".profile");
                    try (var result = statement.executeQuery()) {
                        result.next();
                        if (result.getString(1) != null)
                            throw new SQLException("Research schema already initialized; no data changed");
                    }
                }
                String ddl;
                try (var input = ResearchSchema.class.getResourceAsStream("/db/research/V1__research.sql")) {
                    ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("${schema}", schema)
                            .replace("${dimensions}", Integer.toString(dimensions));
                }
                try (var statement = connection.createStatement()) {
                    statement.execute(ddl);
                }
                try (var statement = connection.prepareStatement("insert into " + schema + ".profile values(true,1,?,?)")) {
                    statement.setString(1, model);
                    statement.setInt(2, dimensions);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static boolean supported(String version) {
        String[] parts = version.split("\\.");
        return Integer.parseInt(parts[0]) > 0 || Integer.parseInt(parts[1]) >= 8;
    }
}
