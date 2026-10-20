package docrag.docling;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.chunk.request.HybridChunkDocumentRequest;
import ai.docling.serve.api.chunk.request.options.HybridChunkerOptions;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import docrag.DocumentExtractor;
import docrag.Research;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Official Java SDK adapter. Docling Serve is an optional, separately operated extraction backend.
 */
public final class DoclingExtractor implements DocumentExtractor {
    private final DoclingServeApi api;
    private final Duration timeout;
    private final String backendVersion;
    private final String tokenizer;
    private final int maxTokens;
    private final ObjectMapper json = new ObjectMapper();

    public DoclingExtractor(String endpoint, Duration timeout) {
        this(endpoint, timeout, "operator-configured-backend");
    }

    public DoclingExtractor(String endpoint, Duration timeout, String backendVersion) {
        this(endpoint, timeout, backendVersion, "google/embeddinggemma-300m", 512);
    }

    public DoclingExtractor(String endpoint, Duration timeout, String backendVersion, String tokenizer, int maxTokens) {
        this.timeout = timeout;
        Research.required(backendVersion, "Docling backend version");
        Research.required(tokenizer, "tokenizer");
        if (maxTokens < 64 || maxTokens > 1024 || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid Docling limits");
        this.backendVersion = backendVersion;
        this.tokenizer = tokenizer;
        this.maxTokens = maxTokens;
        api = DoclingServeApi.builder().baseUrl(endpoint).connectTimeout(Duration.ofSeconds(10)).readTimeout(timeout).build();
    }

    static List<Research.Page> pages(JsonNode document, int maxPages) throws IOException {
        var declaredPages = document.path("pages");
        if (!declaredPages.isObject() || declaredPages.isEmpty() || declaredPages.size() > maxPages)
            throw new IOException("Invalid Docling page coverage");
        Map<Integer, StringBuilder> pages = new TreeMap<>();
        Set<String> visited = new HashSet<>();
        append(document, document.path("body"), pages, visited, 0);
        List<Research.Page> result = new ArrayList<>();
        var names = declaredPages.fieldNames();
        while (names.hasNext()) {
            int page = Integer.parseInt(names.next());
            if (!pages.containsKey(page) || pages.get(page).toString().isBlank())
                throw new IOException("Missing extracted content for page " + page);
        }
        if (pages.size() != declaredPages.size())
            throw new IOException("Docling provenance does not match declared pages");
        pages.forEach((page, text) -> result.add(new Research.Page(page, text.toString())));
        return List.copyOf(result);
    }

    private static void append(JsonNode document, JsonNode item, Map<Integer, StringBuilder> pages, Set<String> visited, int depth) throws IOException {
        if (depth > 100) throw new IOException("Docling document hierarchy exceeds depth limit");
        if (item.has("$ref")) {
            String ref = item.path("$ref").asText();
            if (!ref.startsWith("#/") || !visited.add(ref))
                throw new IOException("Invalid or repeated Docling reference");
            item = document.at(ref.substring(1));
            if (item.isMissingNode()) throw new IOException("Broken Docling reference");
        }
        String text = item.path("text").asText("");
        if (item.path("data").path("table_cells").isArray()) {
            // Preserve explicit row/column coordinates so cells are not mistaken for prose or trusted numeric facts.
            var rows = new StringBuilder("Table cells (row,column,text):\n");
            for (var cell : item.path("data").path("table_cells"))
                rows.append(cell.path("start_row_offset_idx").asInt())
                        .append(',').append(cell.path("start_col_offset_idx").asInt()).append(": ").append(cell.path("text").asText()).append('\n');
            text = rows.toString();
        }
        if (!text.isBlank()) {
            var provenance = item.path("prov");
            if (!provenance.isArray() || provenance.isEmpty())
                throw new IOException("Extracted content lacks page provenance");
            Set<Integer> itemPages = new HashSet<>();
            for (var location : provenance) itemPages.add(location.path("page_no").asInt());
            if (itemPages.size() > 1)
                throw new IOException("Multi-page item lacks unambiguous page-level text; provide page-local conversion");
            itemPages.clear();
            for (var location : provenance) {
                int page = location.path("page_no").asInt();
                if (page < 1) throw new IOException("Invalid provenance page");
                if (itemPages.add(page))
                    pages.computeIfAbsent(page, ignored -> new StringBuilder()).append(text).append('\n');
            }
        }
        for (var child : item.path("children")) append(document, child, pages, visited, depth + 1);
    }

    public String version() {
        return "docling-java-0.7.0/hybrid-v1/" + backendVersion + "/" + tokenizer + "/" + maxTokens;
    }

    @Override
    public List<Research.Chunk> chunks(Path file, int maxPages, int maxChunks) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Docling conversion cancelled");
        if (Files.size(file) > 50L * 1024 * 1024) throw new IOException("Docling upload limit is 50 MiB");
        var request = HybridChunkDocumentRequest.builder()
                .source(FileSource.builder().filename(file.getFileName().toString())
                        .base64String(Base64.getEncoder().encodeToString(Files.readAllBytes(file))).build())
                .options(ConvertDocumentOptions.builder().toFormat(OutputFormat.JSON).doOcr(true).doTableStructure(true)
                        .abortOnError(true).documentTimeout(timeout).build())
                .includeConvertedDoc(true)
                .chunkingOptions(HybridChunkerOptions.builder().maxTokens(maxTokens).tokenizer(tokenizer).mergePeers(true)
                        .includeRawText(true).useMarkdownTables(true).build()).build();
        var response = api.chunkSourceWithHybridChunker(request);
        if (response.getDocuments() == null || response.getDocuments().size() != 1 || response.getChunks() == null
                || response.getChunks().isEmpty() || response.getChunks().size() > maxChunks)
            throw new IOException("Incomplete Docling chunk conversion");
        var document = response.getDocuments().getFirst();
        if (!"success".equalsIgnoreCase(document.getStatus()) || document.getContent() == null
                || document.getContent().getJsonContent() == null || (document.getErrors() != null && !document.getErrors().isEmpty()))
            throw new IOException("Docling reported partial/failed extraction");
        var pages = json.valueToTree(document.getContent().getJsonContent()).path("pages");
        if (!pages.isObject() || pages.isEmpty() || pages.size() > maxPages)
            throw new IOException("Invalid document page coverage");
        Set<Integer> covered = new HashSet<>();
        List<Research.Chunk> result = new ArrayList<>();
        Set<Integer> ordinals = new HashSet<>();
        for (var chunk : response.getChunks().stream().sorted(Comparator.comparingInt(c -> c.getChunkIndex())).toList()) {
            if (!ordinals.add(chunk.getChunkIndex()) || chunk.getRawText() == null || chunk.getRawText().isBlank()
                    || chunk.getNumTokens() == null || chunk.getNumTokens() > maxTokens || chunk.getPageNumbers() == null)
                throw new IOException("Incomplete/oversized Docling chunk");
            for (int page : chunk.getPageNumbers()) {
                if (!pages.has(Integer.toString(page)))
                    throw new IOException("Chunk page is outside the source document");
                covered.add(page);
            }
            result.add(new Research.Chunk(chunk.getRawText(), chunk.getText(), chunk.getPageNumbers(),
                    chunk.getHeadings() == null ? List.of() : chunk.getHeadings(), chunk.getNumTokens()));
        }
        var declared = pages.fieldNames();
        while (declared.hasNext()) if (!covered.contains(Integer.parseInt(declared.next())))
            throw new IOException("A source page has no extracted evidence");
        return List.copyOf(result);
    }

    public List<Research.Page> extract(Path file, int maxPages) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Docling extraction cancelled");
        if (Files.size(file) > 50L * 1024 * 1024) throw new IOException("Docling adapter upload limit is 50 MiB");
        var request = ConvertDocumentRequest.builder()
                .source(FileSource.builder().filename(file.getFileName().toString()).base64String(Base64.getEncoder().encodeToString(Files.readAllBytes(file))).build())
                .options(ConvertDocumentOptions.builder().toFormat(OutputFormat.JSON).doOcr(true).doTableStructure(true)
                        .abortOnError(true).documentTimeout(timeout).build()).build();
        var response = api.convertSource(request);
        if (!(response instanceof InBodyConvertDocumentResponse body) || body.getDocument() == null
                || body.getDocument().getJsonContent() == null || !"success".equalsIgnoreCase(body.getStatus())
                || (body.getErrors() != null && !body.getErrors().isEmpty()))
            throw new IOException("Docling returned incomplete conversion; no pages were accepted");
        return pages(json.valueToTree(body.getDocument().getJsonContent()), maxPages);
    }
}
