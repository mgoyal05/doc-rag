package docrag.example;

import docrag.OllamaGateway;
import docrag.Research;
import docrag.ResearchChatbot;
import docrag.ResearchClient;
import docrag.docling.DoclingExtractor;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Standalone consumer. PostgreSQL/pgvector, Docling and Ollama are configured independently of Spring.
 */
public final class CompanyResearchExample {
    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("Usage: prior.pdf current.pdf 'natural language question'");
        var model = new OllamaGateway(System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434"),
                System.getenv().getOrDefault("CHAT_MODEL", "qwen2.5:14b"), System.getenv().getOrDefault("EMBEDDING_MODEL", "embeddinggemma"), Duration.ofMinutes(3));
        var extractor = new DoclingExtractor(DatabaseSettings.required("RESEARCH_DOCLING_URL"), Duration.ofMinutes(5), DatabaseSettings.required("RESEARCH_DOCLING_VERSION"));
        var options = new ResearchClient.Options(Path.of("research-data"), DatabaseSettings.schema(), DatabaseSettings.dimensions(), Research.Settings.defaults(), 8, Duration.ofMinutes(30), Duration.ofSeconds(60), false);
        try (var chat = ResearchClient.builder(DatabaseSettings.source(), model, extractor).options(options).build()) {
            var conversation = chat.createConversation("standalone-example");
            System.out.println("Conversation: " + conversation.id());
            for (int i = 0; i < 2; i++) {
                Path file = Path.of(args[i]);
                var job = chat.submitUpload("standalone-example", conversation.id(), file, file.getFileName().toString());
                chat.await(job.id(), "standalone-example", Duration.ofMinutes(35));
                var result = chat.history("standalone-example", conversation.id()).getLast().result();
                System.out.println(result);
                if (result == null)
                    throw new IllegalStateException("Upload was interrupted; retry the turn explicitly");
                if (result.outcome() != ResearchChatbot.Outcome.DOCUMENT_READY)
                    return; // Continue a clarification through reply using the printed ID.
            }
            var question = chat.submitMessage("standalone-example", conversation.id(), args[2]);
            chat.await(question.id(), "standalone-example", Duration.ofMinutes(35));
            System.out.println(chat.history("standalone-example", conversation.id()).getLast().result());
        }
    }
}
