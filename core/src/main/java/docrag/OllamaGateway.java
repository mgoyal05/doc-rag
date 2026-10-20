package docrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.output.FinishReason;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * LangChain4j-backed Ollama adapter. No downloads, cloud lifecycle, global pools or automatic retries.
 */
public final class OllamaGateway implements ModelGateway {
    private static final ResponseFormat CLAIMS = claimsFormat();
    private final OllamaChatModel verifier;

    private final OllamaChatModel chat;
    private final OllamaEmbeddingModel embeddings;
    private final String embeddingIdentity;
    private final Profile profile;
    private final ResearchObserver observer;
    private final ThreadLocal<String> requestRole = new ThreadLocal<>();
    private final ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public OllamaGateway(String endpoint, String chatModel, String embeddingModel, Duration timeout, int contextTokens) {
        this(endpoint, chatModel, embeddingModel, timeout, new Profile(contextTokens, 2048, 4, false, ""), ResearchObserver.NONE);
    }

    public OllamaGateway(String endpoint, String chatModel, String embeddingModel, Duration timeout) {
        this(endpoint, chatModel, embeddingModel, timeout, 16384);
    }

    public OllamaGateway(String endpoint, String chatModel, String embeddingModel, Duration timeout, Profile profile, ResearchObserver observer) {
        URI uri = URI.create(endpoint);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("An HTTP(S) Ollama endpoint is required");
        Research.required(chatModel, "chat model");
        Research.required(embeddingModel, "embedding model");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required");
        this.profile = Objects.requireNonNull(profile);
        this.observer = ResearchObserver.safe(observer);
        chat = chat(endpoint, chatModel, timeout, "generation");
        verifier = profile.verifierModel().isBlank() || profile.verifierModel().equals(chatModel)
                ? chat : chat(endpoint, profile.verifierModel(), timeout, "verification");
        embeddings = OllamaEmbeddingModel.builder().baseUrl(endpoint).modelName(embeddingModel).timeout(timeout).maxRetries(0).httpClientBuilder(new NonTruncatingEmbeddingTransport(profile.cpuThreads(), this.observer)).build();
        embeddingIdentity = embeddingModel;
    }

    private static ResponseFormat claimsFormat() {
        var fact = JsonObjectSchema.builder();
        for (String field : List.of("id", "evidenceId", "metric", "value", "currency", "unit", "basis", "quote"))
            fact.addStringProperty(field);
        fact.required("id", "evidenceId", "metric", "value", "currency", "unit", "basis", "quote");
        var root = JsonObjectSchema.builder().addProperty("claims", JsonArraySchema.builder().items(JsonObjectSchema.builder()
                .addStringProperty("text").addProperty("citations", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                .required("text", "citations").build()).build()).addProperty("facts", JsonArraySchema.builder().items(fact.build()).build()).required("claims");
        return ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(JsonSchema.builder().rootElement(root.build()).build()).build();
    }

    private OllamaChatModel chat(String endpoint, String name, Duration timeout, String role) {
        return OllamaChatModel.builder().baseUrl(endpoint).modelName(name).temperature(0.0)
                .numCtx(profile.contextTokens()).numPredict(profile.outputTokens()).think(profile.thinking()).returnThinking(false)
                .timeout(timeout).maxRetries(0).truncate(false)
                .httpClientBuilder(new BoundedTransport(profile.cpuThreads(), observer, () -> requestRole.get() == null ? role : requestRole.get())).build();
    }

    public float[] embed(String text) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Embedding cancelled");
        try (var span = observer.start("embedding", null)) {
            var vector = embeddings.embed(text).content().vector();
            span.finish("OK", 1);
            return vector;
        }
    }

    public String embeddingIdentity() {
        return embeddingIdentity;
    }

    public String structured(Structure kind, String system, String prompt) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Planning cancelled");
        if (prompt.length() > 32000) throw new IllegalArgumentException("Planning context limit exceeded");
        var object = JsonObjectSchema.builder();
        if (kind == Structure.QUERY_SCOPE) {
            object.addProperty("targets", JsonArraySchema.builder().items(JsonObjectSchema.builder().addStringProperty("company")
                    .addStringProperty("period").required("company", "period").build()).build());
            object.addStringProperty("clarification").addStringProperty("standaloneQuestion")
                    .required("targets", "clarification", "standaloneQuestion");
        } else if (kind == Structure.VERIFICATION) {
            object.addProperty("verdicts", JsonArraySchema.builder().items(JsonObjectSchema.builder()
                    .addStringProperty("id").addBooleanProperty("supported").addStringProperty("reason")
                    .required("id", "supported", "reason").build()).build()).required("verdicts");
        } else {
            object.addStringProperty("company").addStringProperty("companyQuote").addStringProperty("clarification")
                    .addProperty("periods", JsonArraySchema.builder().items(JsonObjectSchema.builder().addStringProperty("label")
                            .addStringProperty("start").addStringProperty("end").addStringProperty("quote")
                            .required("label", "start", "end", "quote").build()).build())
                    .required("company", "companyQuote", "periods", "clarification");
        }
        var format = ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().rootElement(object.build()).build()).build();
        return NativeGuardrails.run(system, prompt, request -> checked(kind == Structure.VERIFICATION ? verifier : chat,
                request.toBuilder().parameters(request.parameters().overrideWith(dev.langchain4j.model.chat.request.ChatRequestParameters.builder()
                        .responseFormat(format).build())).build(), kind.name().toLowerCase(Locale.ROOT)), root -> {
            if (kind == Structure.VERIFICATION && (!root.path("verdicts").isArray() || root.size() != 1))
                throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
            if (kind == Structure.QUERY_SCOPE && (!root.path("targets").isArray() || !root.path("clarification").isTextual()
                    || !root.path("standaloneQuestion").isTextual() || root.size() != 3))
                throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
            if (kind == Structure.DOCUMENT_METADATA && (!root.path("company").isTextual() || !root.path("companyQuote").isTextual()
                    || !root.path("periods").isArray() || !root.path("clarification").isTextual() || root.size() != 4))
                throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
        });
    }

    public Research.Generation generate(String system, String prompt, List<Research.Tool> tools) throws Exception {
        List<Research.ToolEvidence> evidence = new ArrayList<>();
        String result = NativeGuardrails.run(system, prompt, request -> {
            try {
                return generate(request.messages(), tools, evidence);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new ResearchFailure(ResearchFailure.Code.MODEL_UNAVAILABLE);
            }
        }, root -> {
            if (root.size() > 2 || !root.path("claims").isArray() || root.path("claims").size() > 40 || root.has("facts") && !root.path("facts").isArray())
                throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
        });
        return new Research.Generation(result, evidence);
    }

    private ChatResponse generate(List<ChatMessage> initial, List<Research.Tool> tools, List<Research.ToolEvidence> evidence) throws Exception {
        Map<String, Research.Tool> registry = new LinkedHashMap<>();
        List<ToolSpecification> specifications = new ArrayList<>();
        if (tools.size() > 8) throw new IllegalArgumentException("At most eight registered tools");
        for (var tool : tools) {
            if (!tool.name().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || registry.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalArgumentException("Invalid or duplicate tool name");
            var schema = JsonObjectSchema.builder();
            for (String argument : tool.arguments()) schema.addStringProperty(argument);
            schema.required(tool.arguments());
            specifications.add(ToolSpecification.builder().name(tool.name()).description(tool.description()).parameters(schema.build()).build());
        }
        List<ChatMessage> messages = new ArrayList<>(initial);
        int calls = 0;
        for (int round = 0; round < 3; round++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Generation cancelled");
            var builder = ChatRequest.builder().messages(messages);
            if (!specifications.isEmpty() && round < 2) builder.toolSpecifications(specifications);
            else builder.responseFormat(CLAIMS);
            var reply = checked(chat, builder.build(), "generation");
            var response = reply.aiMessage();
            messages.add(response);
            if (!response.hasToolExecutionRequests()) return reply;
            if (round == 2) throw new IllegalStateException("Model requested tools after final-response boundary");
            for (var request : response.toolExecutionRequests()) {
                if (++calls > 4) throw new IllegalStateException("Tool call budget exceeded");
                var tool = registry.get(request.name());
                if (tool == null) throw new IllegalArgumentException("Model requested an unregistered tool");
                var node = json.readTree(request.arguments());
                if (!node.isObject() || node.size() != tool.arguments().size())
                    throw new IllegalArgumentException("Invalid tool arguments");
                Map<String, String> arguments = new LinkedHashMap<>();
                for (String name : tool.arguments()) {
                    if (!node.path(name).isTextual() || node.path(name).asText().length() > 256)
                        throw new IllegalArgumentException("Missing/invalid tool argument: " + name);
                    arguments.put(name, node.path(name).asText());
                }
                Research.ToolResult result;
                try (var span = observer.start("tool", null)) {
                    result = tool.execute(Map.copyOf(arguments));
                    if (result == null || result.text() == null || result.text().isBlank() || result.text().length() > 8000
                            || result.source() == null || result.source().isBlank() || result.source().length() > 4096)
                        throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
                    span.finish("OK", 1);
                }
                if (result == null || result.text() == null || result.text().length() > 8000)
                    throw new IllegalStateException("Invalid tool result");
                String id = "T" + calls;
                if (result.facts().size() > 80) throw new ResearchFailure(ResearchFailure.Code.RESOURCE_LIMIT);
                var facts = result.facts().stream().map(f -> f.referenced(id + "_" + f.id(), id)).toList();
                String payload = json.writeValueAsString(Map.of("data", result.text(), "facts", facts));
                if (payload.length() > 16000) throw new ResearchFailure(ResearchFailure.Code.RESOURCE_LIMIT);
                evidence.add(new Research.ToolEvidence(id, result.text(), result.source(), facts));
                messages.add(ToolExecutionResultMessage.from(request, "Evidence " + id + ": " + payload));
            }
        }
        throw new IllegalStateException("Tool round budget exceeded");
    }

    private ChatResponse checked(OllamaChatModel selected, ChatRequest request, String role) {
        if (Thread.currentThread().isInterrupted()) throw new ResearchFailure(ResearchFailure.Code.CANCELLED);
        // Covers the complete growing request. This is a character guard, not a tokenizer claim.
        long chars = request.messages().stream().mapToLong(m -> m.toString().length()).sum()
                + request.toolSpecifications().stream().mapToLong(t -> t.toString().length()).sum();
        if (chars > Math.min(64000L, (long) profile.contextTokens() * 3))
            throw new ResearchFailure(ResearchFailure.Code.RESOURCE_LIMIT);
        try (var span = observer.start(role, null)) {
            requestRole.set(role);
            var result = selected.chat(request);
            if (result.finishReason() == FinishReason.LENGTH)
                throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
            if (result.tokenUsage() != null && result.tokenUsage().inputTokenCount() != null
                    && result.tokenUsage().inputTokenCount() + profile.outputTokens() > profile.contextTokens())
                throw new ResearchFailure(ResearchFailure.Code.RESOURCE_LIMIT);
            span.finish("OK", 1);
            return result;
        } finally {
            requestRole.remove();
        }
    }

    public record Profile(int contextTokens, int outputTokens, int cpuThreads, boolean thinking, String verifierModel) {
        public Profile {
            if (contextTokens < 4096 || outputTokens < 256 || outputTokens > contextTokens / 2 || cpuThreads < 1 || cpuThreads > 64)
                throw new IllegalArgumentException("Invalid model profile");
            verifierModel = verifierModel == null ? "" : verifierModel.trim();
        }
    }

    /**
     * Inject the SDK's missing CPU option and record only allowlisted numerical response metadata.
     */
    private static final class BoundedTransport implements HttpClientBuilder {
        private final HttpClientBuilder delegate = JdkHttpClient.builder();
        private final int threads;
        private final ResearchObserver observer;
        private final java.util.function.Supplier<String> role;

        BoundedTransport(int threads, ResearchObserver observer, java.util.function.Supplier<String> role) {
            this.threads = threads;
            this.observer = observer;
            this.role = role;
        }

        private static Long number(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.path(field).isIntegralNumber() && node.path(field).asLong() >= 0 ? node.path(field).asLong() : null;
        }

        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        public HttpClientBuilder connectTimeout(Duration value) {
            delegate.connectTimeout(value);
            return this;
        }

        public HttpClientBuilder readTimeout(Duration value) {
            delegate.readTimeout(value);
            return this;
        }

        public HttpClient build() {
            var client = delegate.build();
            return new HttpClient() {
                public SuccessfulHttpResponse execute(HttpRequest request) {
                    try {
                        var mapper = new ObjectMapper();
                        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(request.body());
                        body.withObject("/options").put("num_thread", threads);
                        var response = client.execute(HttpRequest.builder().method(request.method()).url(request.url()).headers(request.headers()).body(mapper.writeValueAsString(body)).build());
                        try {
                            var metadata = mapper.readTree(response.body());
                            observer.usage(new ResearchObserver.Usage(role.get(), body.path("model").asText(), number(metadata, "prompt_eval_count"), number(metadata, "eval_count"),
                                    number(metadata, "load_duration"), number(metadata, "prompt_eval_duration"), number(metadata, "eval_duration")));
                        } catch (Exception ignored) {
                        }
                        return response;
                    } catch (java.io.IOException invalid) {
                        throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
                    }
                }

                public void execute(HttpRequest request, dev.langchain4j.http.client.sse.ServerSentEventParser parser, dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                    throw new UnsupportedOperationException("Model token streaming is disabled");
                }
            };
        }
    }

    /**
     * LangChain4j 1.22's embedding model does not expose Ollama's truncate option. Enforce it at its HTTP extension point.
     */
    private static final class NonTruncatingEmbeddingTransport implements HttpClientBuilder {
        private final HttpClientBuilder delegate = JdkHttpClient.builder();
        private final int threads;
        private final ResearchObserver observer;

        NonTruncatingEmbeddingTransport(int threads, ResearchObserver observer) {
            this.threads = threads;
            this.observer = observer;
        }

        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        public HttpClientBuilder connectTimeout(Duration value) {
            delegate.connectTimeout(value);
            return this;
        }

        public HttpClientBuilder readTimeout(Duration value) {
            delegate.readTimeout(value);
            return this;
        }

        public HttpClient build() {
            var client = delegate.build();
            return new HttpClient() {
                public SuccessfulHttpResponse execute(HttpRequest request) {
                    try {
                        var mapper = new ObjectMapper();
                        var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(request.body());
                        body.put("truncate", false);
                        body.withObject("/options").put("num_thread", threads);
                        var response = client.execute(HttpRequest.builder().method(request.method()).url(request.url()).headers(request.headers()).body(mapper.writeValueAsString(body)).build());
                        try {
                            var metadata = mapper.readTree(response.body());
                            observer.usage(new ResearchObserver.Usage("embedding", body.path("model").asText(), BoundedTransport.number(metadata, "prompt_eval_count"), null,
                                    BoundedTransport.number(metadata, "load_duration"), null, null));
                        } catch (Exception ignored) {
                        }
                        return response;
                    } catch (java.io.IOException e) {
                        throw new IllegalArgumentException("Cannot encode non-truncating embedding request", e);
                    }
                }

                public void execute(HttpRequest request, dev.langchain4j.http.client.sse.ServerSentEventParser parser, dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                    throw new UnsupportedOperationException("Embedding transport is synchronous");
                }
            };
        }
    }
}
