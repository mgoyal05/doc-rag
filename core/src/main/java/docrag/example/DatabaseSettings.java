package docrag.example;

import docrag.ResearchSchema;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Example-only environment configuration. Never reads the parent application's credentials.
 */
public final class DatabaseSettings {
    private DatabaseSettings() {
    }

    public static PGSimpleDataSource source() {
        var source = new PGSimpleDataSource();
        source.setURL(required("RESEARCH_JDBC_URL"));
        source.setUser(required("RESEARCH_DB_USER"));
        source.setPassword(required("RESEARCH_DB_PASSWORD"));
        return source;
    }

    public static String schema() {
        return System.getenv().getOrDefault("RESEARCH_SCHEMA", "document_research");
    }

    public static int dimensions() {
        return Integer.parseInt(System.getenv().getOrDefault("RESEARCH_EMBEDDING_DIMENSIONS", "768"));
    }

    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("initialize"))
            throw new IllegalArgumentException("Usage: DatabaseSettings initialize (explicit new research schema setup)");
        ResearchSchema.initialize(source(), schema(), dimensions(), System.getenv().getOrDefault("EMBEDDING_MODEL", "embeddinggemma"));
        System.out.println("Research schema initialized; no historical application migrations were run.");
    }
}
