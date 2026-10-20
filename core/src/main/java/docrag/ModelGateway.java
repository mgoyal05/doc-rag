package docrag;

import java.util.List;

/**
 * Provider boundary; also permits deterministic tests without contacting an inference server.
 */
public interface ModelGateway {
    String embeddingIdentity();

    float[] embed(String text) throws Exception;

    Research.Generation generate(String system, String prompt, List<Research.Tool> tools) throws Exception;

    default String structured(Structure kind, String system, String prompt) throws Exception {
        throw new UnsupportedOperationException("Structured query planning is not configured");
    }

    enum Structure {DOCUMENT_METADATA, QUERY_SCOPE, VERIFICATION}
}
