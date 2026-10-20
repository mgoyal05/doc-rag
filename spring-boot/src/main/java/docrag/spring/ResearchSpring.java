package docrag.spring;

import docrag.*;
import docrag.docling.DoclingExtractor;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Optional Spring setup factory. The core client remains independently usable without Spring.
 */
public final class ResearchSpring {
    private ResearchSpring() {
    }

    public static ResearchClient create(ResearchProperties p, DataSource source, ToolRegistry tools,
                                        Function<Set<String>, Map<String, String>> aliases, ResearchClient.ManagedResource resource,
                                        ResearchClient.Enrichment enrichment, ResearchObserver observer) throws Exception {
        if (p.isSharedHost() && p.isManageVm())
            throw new IllegalArgumentException("Managed VM shutdown is forbidden on the shared application host");
        if (p.isInitializeSchema())
            ResearchSchema.initializeIfMissing(source, p.getSchema(), p.getEmbeddingDimensions(), p.getEmbeddingModel());
        var model = new OllamaGateway(p.getOllamaUrl(), p.getChatModel(), p.getEmbeddingModel(), p.getModelTimeout(),
                new OllamaGateway.Profile(p.getModelContextTokens(), p.getModelOutputTokens(), p.getModelCpuThreads(), p.isModelThinking(), p.getVerifierModel()), observer);
        var extractor = new DoclingExtractor(p.getDoclingUrl(), p.getModelTimeout(), p.getDoclingVersion(), p.getTokenizer(), p.getChunkTokens());
        var defaults = Research.Settings.defaults();
        var limits = new Research.Settings(defaults.contextChars(), defaults.topK(), p.getMaxFileBytes(), defaults.maxPages(), defaults.maxChunks());
        return ResearchClient.builder(source, model, extractor)
                .options(new ResearchClient.Options(p.getDirectory(), p.getSchema(), p.getEmbeddingDimensions(), limits, p.getQueueCapacity(), p.getJobTimeout(), p.getIdleGrace(), p.isManageVm()))
                .perOwnerCapacity(p.getPerOwnerCapacity()).tools(tools).aliases(aliases).managedResource(resource).enrichment(enrichment).observer(observer)
                .sourceLocator(id -> "/api/research/documents/" + id + "/file").build();
    }
}
