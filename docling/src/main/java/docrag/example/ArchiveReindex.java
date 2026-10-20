package docrag.example;

import docrag.*;
import docrag.docling.DoclingExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Explicit bounded PDF import. Reads no Lucene files and deletes no source/archive data.
 */
public final class ArchiveReindex {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: archiveDirectory maximumFiles (1-100)");
        Path archive = Path.of(args[0]).toRealPath();
        int maximum = Integer.parseInt(args[1]);
        if (maximum < 1 || maximum > 100) throw new IllegalArgumentException("Import limit is 1-100");
        var model = new OllamaGateway(System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434"),
                System.getenv().getOrDefault("CHAT_MODEL", "qwen2.5:14b"), System.getenv().getOrDefault("EMBEDDING_MODEL", "embeddinggemma"), Duration.ofMinutes(3));
        var store = new PostgresResearchStore(DatabaseSettings.source(), DatabaseSettings.schema(), model.embeddingIdentity(), DatabaseSettings.dimensions());
        try (var engine = new ResearchEngine(Research.Settings.defaults(), model, new DoclingExtractor(DatabaseSettings.required("RESEARCH_DOCLING_URL"), Duration.ofMinutes(5), DatabaseSettings.required("RESEARCH_DOCLING_VERSION")), store);
             var paths = Files.list(archive)) {
            var chatbot = new ResearchChatbot(store, engine, model, new ToolRegistry(), companies -> Map.of());
            String conversation = chatbot.createConversation("archive-import").id();
            int count = 0;
            for (Path file : paths.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).limit(maximum).toList()) {
                var result = chatbot.uploadDocument("archive-import", conversation, file);
                System.out.println(file.getFileName() + ": " + result.outcome() + " " + result.documentId());
                count++;
                if (result.outcome() == ResearchChatbot.Outcome.CLARIFICATION_REQUIRED) break;
            }
            System.out.println("Processed " + count + " files. Conversation: " + conversation + ". Source files remain unchanged.");
        }
    }
}
