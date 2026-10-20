package docrag;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable public values. No Spring, persistence, or model-provider types escape the API.
 */
public final class Research {
    private Research() {
    }

    public static void required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 4096)
            throw new IllegalArgumentException("Invalid " + name);
    }

    public enum Status {ANSWERED, INSUFFICIENT_EVIDENCE}

    /**
     * A host-registered read-only capability. Implementations must not execute arbitrary SQL or URLs.
     */
    public interface Tool {
        String name();

        String description();

        List<String> arguments();

        ToolResult execute(Map<String, String> arguments) throws Exception;
    }

    public record Settings(int contextChars, int topK, long maxFileBytes, int maxPages, int maxChunks) {
        public Settings {
            if (contextChars < 8192
                    || topK < 1 || topK > 100 || maxFileBytes < 1 || maxPages < 1 || maxChunks < 1)
                throw new IllegalArgumentException("Invalid research limits");
        }

        public static Settings defaults() {
            return new Settings(32000, 8, 50L * 1024 * 1024, 500, 2000);
        }
    }

    public record Document(String id, String company, String period, String source, Path file) {
        public Document {
            required(id, "document id");
            required(company, "company");
            required(period, "period");
            source = source == null ? "" : source;
            Objects.requireNonNull(file);
        }
    }

    public record Page(int number, String text) {
        public Page {
            if (number < 1 || text == null || text.isBlank()) throw new IllegalArgumentException("Invalid page");
        }
    }

    public record Question(String company, List<String> periods, String text) {
        public Question {
            required(company, "company");
            required(text, "question");
            periods = List.copyOf(periods);
            if (periods.isEmpty() || periods.size() > 4 || periods.stream().anyMatch(p -> p == null || p.isBlank())
                    || periods.stream().distinct().count() != periods.size() || text.length() > 4000)
                throw new IllegalArgumentException("Provide 1-4 distinct reporting periods and a question up to 4000 characters");
        }
    }

    public record Chunk(String text, String embeddingText, List<Integer> pages, List<String> headings, int tokens) {
        public Chunk {
            if (text == null || text.isBlank() || text.length() > 16000)
                throw new IllegalArgumentException("Invalid chunk text");
            if (embeddingText == null || embeddingText.isBlank())
                throw new IllegalArgumentException("Empty embedding text");
            pages = List.copyOf(pages);
            headings = List.copyOf(headings);
            if (pages.isEmpty() || pages.stream().anyMatch(p -> p < 1) || tokens < 1)
                throw new IllegalArgumentException("Chunk requires page provenance and token count");
        }
    }

    public record Period(String label, String start, String end) {
        public Period {
            required(label, "period label");
            if (LocalDate.parse(start).isAfter(LocalDate.parse(end)))
                throw new IllegalArgumentException("Invalid period dates");
        }

        public String key() {
            return start + "/" + end;
        }
    }

    public record Target(String company, String period) {
        public Target {
            required(company, "company");
            required(period, "period");
        }
    }

    public record Scope(List<Target> targets) {
        public Scope {
            targets = List.copyOf(targets);
            if (targets.isEmpty() || targets.stream().distinct().count() != targets.size()
                    || targets.stream().map(Target::company).distinct().count() > 2
                    || targets.stream().map(Target::period).distinct().count() > 4)
                throw new IllegalArgumentException("Scope supports two companies and four periods");
        }
    }

    public record Evidence(String id, String documentId, String company, String period, String source, int page,
                           String text,
                           List<Integer> pages, List<String> headings) {
        public Evidence {
            pages = pages == null ? (page > 0 ? List.of(page) : List.of()) : List.copyOf(pages);
            headings = headings == null ? List.of() : List.copyOf(headings);
        }

        public Evidence(String id, String documentId, String company, String period, String source, int page, String text) {
            this(id, documentId, company, period, source, page, text, null, null);
        }
    }

    public record Claim(String text, List<String> citations, List<Fact> facts) {
        public Claim {
            required(text, "claim");
            citations = List.copyOf(citations);
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        public Claim(String text, List<String> citations) {
            this(text, citations, List.of());
        }
    }

    public record ValidationReport(String policyVersion, boolean accepted, int checkedClaims, String code) {
    }

    /**
     * A stated quantity or a Java-calculated quantity, never an unchecked model calculation.
     */
    public record Fact(String id, String evidenceId, String metric, java.math.BigDecimal value,
                       String currency, String unit, String basis, String asOf, String source,
                       List<Target> targets, String quote, boolean calculated) {
        public Fact {
            required(id, "fact id");
            required(metric, "metric");
            Objects.requireNonNull(value);
            if (value.precision() > 40 || Math.abs(value.scale()) > 12)
                throw new IllegalArgumentException("Invalid fact precision");
            required(currency, "currency");
            required(unit, "unit");
            required(basis, "basis");
            required(asOf, "as-of");
            required(source, "source");
            targets = List.copyOf(targets);
            if (targets.isEmpty() || targets.size() > 2) throw new IllegalArgumentException("Invalid fact scope");
            quote = quote == null ? "" : quote;
        }

        public String formatted() {
            return value.stripTrailingZeros().toPlainString() + " " + ("N/A".equals(currency) || unit.startsWith(currency + " ") ? unit : currency + " " + unit);
        }

        public Fact referenced(String reference, String evidence) {
            return new Fact(reference, evidence, metric, value, currency, unit, basis, asOf, source, targets, quote, calculated);
        }
    }

    public record Answer(Status status, List<Claim> claims, List<Evidence> evidence, String message,
                         ValidationReport validation) {
        public Answer {
            claims = List.copyOf(claims);
            evidence = List.copyOf(evidence);
        }

        public Answer(Status status, List<Claim> claims, List<Evidence> evidence, String message) {
            this(status, claims, evidence, message, null);
        }
    }

    public record Ingestion(String documentId, String fingerprint, int chunks, boolean unchanged) {
    }

    public record ToolEvidence(String id, String text, String source, List<Fact> facts) {
        public ToolEvidence {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        public ToolEvidence(String id, String text, String source) {
            this(id, text, source, List.of());
        }
    }

    public record Generation(String json, List<ToolEvidence> tools) {
        public Generation {
            tools = List.copyOf(tools);
        }
    }

    public record ToolResult(String text, String source, List<Fact> facts) {
        public ToolResult {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        public ToolResult(String text, String source) {
            this(text, source, List.of());
        }
    }
}
