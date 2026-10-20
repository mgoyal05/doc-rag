package docrag;

import java.nio.file.Path;
import java.util.List;

public interface DocumentExtractor {
    String version();

    List<Research.Page> extract(Path file, int maxPages) throws Exception;

    /**
     * Structure-aware chunks. Implementations must preserve all source pages or fail explicitly.
     */
    default List<Research.Chunk> chunks(Path file, int maxPages, int maxChunks) throws Exception {
        throw new UnsupportedOperationException("This extractor does not provide structured chunking; configure Docling");
    }
}
