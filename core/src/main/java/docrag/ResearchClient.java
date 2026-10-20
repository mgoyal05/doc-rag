package docrag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;


/**
 * Single bounded background worker. Durable snapshots are scoped to the submitting authenticated owner.
 */
public final class ResearchClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ResearchClient.class);
    private final Options properties;
    private final ResearchEngine engine;
    private final ResearchModelSession model;
    private final ResearchChatbot chatbot;
    private final Enrichment enrichment;
    private final ResearchJobStore store;
    private final ResearchObserver observer;
    private final int perOwnerCapacity;
    private final java.util.concurrent.atomic.AtomicBoolean resourcesClosed = new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "research-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> activeNews = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<Job>>> listeners = new ConcurrentHashMap<>();
    private volatile boolean closing;

    private ResearchClient(Builder builder) throws Exception {
        this.properties = builder.options;
        this.enrichment = builder.enrichment;
        this.observer = ResearchObserver.safe(builder.observer);
        this.perOwnerCapacity = builder.perOwnerCapacity;
        // Take exclusive ownership before touching the model resource or reconciling any durable work.
        this.store = new ResearchJobStore(properties.directory().resolve("jobs"));
        ResearchModelSession session = null;
        try {
            session = new ResearchModelSession(builder.model, builder.resource, properties, observer);
            this.model = session;
            var database = new PostgresResearchStore(builder.source, properties.schema(), model.embeddingIdentity(), properties.embeddingDimensions());
            this.engine = new ResearchEngine(properties.limits(), model, builder.extractor, database, builder.observer);
            this.chatbot = new ResearchChatbot(database, engine, model, builder.tools, builder.aliases, java.time.Clock.systemUTC(), builder.sourceLocator);
        } catch (Exception error) {
            if (session != null) session.close();
            store.close();
            throw error;
        }
        if (properties.queueCapacity() < 1 || properties.queueCapacity() > 100 || properties.jobTimeout().isNegative()
                || properties.jobTimeout().isZero())
            throw new IllegalArgumentException("Invalid research job limits");
        try {
            for (var job : store.recovered()) {
                if (job.request().conversationId() != null)
                    chatbot.interruptTurn(job.owner(), job.request().conversationId(), job.request().turnId());
                if (external(job.request()))
                    enrichment.failed(job.request().documentId(), job.request().newsVersion(), "Research interrupted by restart; retry explicitly");
            }
            // Includes a crash between reserving a DB turn and persisting its job file.
            chatbot.recoverInterruptedTurns();
        } catch (Exception e) {
            store.close();
            model.close();
            throw new IOException("Research job recovery failed", e);
        }
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(properties.queueCapacity()),
                r -> {
                    var thread = new Thread(r, "research-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static boolean external(Request request) {
        return "NEWS".equals(request.kind());
    }

    public static Builder builder(DataSource source, ModelGateway model, DocumentExtractor extractor) {
        return new Builder(source, model, extractor);
    }

    private static Conversation value(PostgresResearchStore.Conversation v) {
        return new Conversation(v.id(), v.title(), v.activeTurn(), v.scope(), v.pendingDocument(), v.updatedAt());
    }

    public Path archive() throws IOException {
        Path path = properties.directory().resolve("archive").toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path;
    }

    public boolean hasExternalDocument(String id) {
        return activeNews.containsKey(id);
    }

    public boolean hasCapacity() {
        return !closing && !worker.isShutdown() && worker.getQueue().remainingCapacity() > 0;
    }

    public Job get(String id, String owner) throws IOException {
        Job job = store.get(id);
        if (!job.owner().equals(owner)) throw new java.nio.file.NoSuchFileException("Research job not found");
        return job;
    }

    private synchronized Job submit(String owner, Request request) throws IOException {
        requireCapacity(owner);
        if (worker.isShutdown() || worker.getQueue().remainingCapacity() == 0)
            throw new RejectedExecutionException("Research queue is full");
        validate(request);
        Job job = store.create(owner, request);
        if (external(request)) activeNews.put(request.documentId(), job.id());
        var context = observer.capture();
        var task = new FutureTask<Void>(() -> {
            try (var attached = context.attach(); var span = observer.start("job", job.id())) {
                observer.completed("queue.wait", java.time.Duration.between(Instant.parse(job.createdAt()), Instant.now()).toNanos(), 1, true);
                observer.gauge("active", 1);
                run(job.id());
                var outcome = store.get(job.id());
                span.finish(outcome.state() == State.COMPLETED || outcome.state() == State.NEEDS_CLARIFICATION ? "OK" : outcome.state().name(), 1);
            } finally {
                observer.gauge("active", 0);
                observer.gauge("queue.depth", worker.getQueue().size());
            }
            return null;
        });
        tasks.put(job.id(), task);
        try {
            worker.execute(task);
            observer.event("admission", "ACCEPTED", 1);
            observer.gauge("queue.depth", worker.getQueue().size());
        } catch (RejectedExecutionException e) {
            tasks.remove(job.id());
            if (external(request)) activeNews.remove(request.documentId(), job.id());
            update(job.id(), State.FAILED, null, "Research queue is full");
            throw e;
        }
        return job;
    }

    /**
     * Single-node admission, bounded by the existing queue; includes non-cooperative cancelled tasks until they exit.
     */
    public synchronized void requireCapacity(String owner) throws IOException {
        Research.required(owner, "authenticated owner");
        int pending = 0;
        for (String id : tasks.keySet()) if (store.get(id).owner().equals(owner)) pending++;
        if (!hasCapacity() || pending >= perOwnerCapacity) {
            observer.event("admission", "RESOURCE_LIMIT", 1);
            throw new RejectedExecutionException("Research capacity exceeded");
        }
    }

    public synchronized Job submitExternal(String owner, ExternalDocument document) throws IOException {
        if (hasExternalDocument(document.key())) return store.get(activeNews.get(document.key()));
        return submit(owner, new Request("NEWS", document.company(), List.of(document.period()), null, document.key(), null, document.source(), null, document.version()));
    }

    public synchronized Job submitMessage(String owner, String conversation, String text) throws Exception {
        requireCapacity(owner);
        String turn = chatbot.reserveMessage(owner, conversation, text);
        return submitTurn(owner, conversation, turn);
    }

    public synchronized Job submitMessage(String owner, String conversation, String text, List<String> documents) throws Exception {
        requireCapacity(owner);
        return submitTurn(owner, conversation, chatbot.reserveMessage(owner, conversation, text, documents));
    }

    public synchronized Job submitUpload(String owner, String conversation, Path file, String displayName) throws Exception {
        requireCapacity(owner);
        String turn = chatbot.reserveUpload(owner, conversation, file, displayName);
        return submitTurn(owner, conversation, turn);
    }

    private Job submitTurn(String owner, String conversation, String turn) throws Exception {
        try {
            return submit(owner, new Request("CHAT", null, List.of(), null, null, null, null, null, null, conversation, turn));
        } catch (Exception e) {
            chatbot.interruptTurn(owner, conversation, turn);
            throw e;
        }
    }

    public synchronized Job retryTurn(String owner, String conversation, String turn) throws Exception {
        requireCapacity(owner);
        return submitTurn(owner, conversation, chatbot.reserveRetry(owner, conversation, turn));
    }

    public String activeJob(String owner, String conversation) throws IOException {
        for (String id : tasks.keySet()) {
            var job = store.get(id);
            if (job.owner().equals(owner) && conversation.equals(job.request().conversationId()) && !job.terminal())
                return id;
        }
        return null;
    }

    public synchronized Job retryExternal(String reference, String owner) throws Exception {
        var document = enrichment.resolve(reference);
        if (hasExternalDocument(document.key()))
            throw new IllegalArgumentException("Document already has active enrichment");
        return submitExternal(owner, document);
    }

    private void validate(Request request) {
        if ("CHAT".equals(request.kind())) {
            java.util.UUID.fromString(request.conversationId());
            java.util.UUID.fromString(request.turnId());
        } else if (Set.of("INGEST", "NEWS", "SUMMARY").contains(request.kind())) {
            Research.required(request.documentId(), "document id");
            Research.required(request.company(), "company");
            if (request.periods().size() != 1)
                throw new IllegalArgumentException("Ingestion/summary requires one reporting period");
            Research.required(request.periods().get(0), "reporting period");
        } else throw new IllegalArgumentException("Unknown research job kind");
    }

    private void run(String id) {
        ScheduledFuture<?> timeout = null;
        Request request = null;
        AutoCloseable progress = null;
        AutoCloseable work = null;
        try {
            Job job = store.get(id);
            request = job.request();
            if (job.terminal()) return;
            work = model.work();
            timeout = deadlines.schedule(() -> cancelInternal(id, "Job deadline exceeded"), properties.jobTimeout().toMillis(), TimeUnit.MILLISECONDS);
            update(id, State.PROCESSING, null, null);
            progress = model.progress(starting -> {
                try {
                    update(id, starting ? State.STARTING_MODEL : State.PROCESSING, null, null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            Research.Answer answer = null;
            if ("CHAT".equals(request.kind())) {
                var result = chatbot.processTurn(job.owner(), request.conversationId(), request.turnId());
                observer.event("conversation", result.outcome().name(), 1);
                update(id, result.outcome() == ResearchChatbot.Outcome.CLARIFICATION_REQUIRED ? State.NEEDS_CLARIFICATION : State.COMPLETED, result.answer(), null);
                return;
            } else {
                Path file = "NEWS".equals(request.kind()) ? download(request.source(), id) : confinedFile(request.file());
                var document = new Research.Document(request.documentId(), request.company(), request.periods().getFirst(), request.source(), file);
                var ingestion = external(request) ? engine.ingestExternal(document) : engine.ingest(document);
                if ("NEWS".equals(request.kind()) || "SUMMARY".equals(request.kind()))
                    answer = engine.summarize(request.documentId());
                if (external(request)) {
                    if (answer == null || answer.status() != Research.Status.ANSWERED)
                        throw new IllegalStateException("Document could not be summarized completely");
                    String summary = String.join(" ", answer.claims().stream().map(Research.Claim::text).toList());
                    if (summary.length() > 1024) throw new IllegalStateException("Summary exceeds alert storage limit");
                    if (store.get(id).state() == State.CANCELLED) return;
                    boolean applied = enrichment.completed(request.documentId(), request.newsVersion(), summary, ingestion.fingerprint());
                    if (!applied) {
                        update(id, State.INTERRUPTED, null, "Source changed; stale result discarded");
                        return;
                    }
                }
            }
            update(id, State.COMPLETED, answer, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelInternal(id, "Research interrupted");
        } catch (Exception e) {
            log.warn("Research job {} failed: {}", id, e.getClass().getSimpleName());
            try {
                Job job = update(id, State.FAILED, null, safeError(e));
                if (request != null && external(request) && job.state() == State.FAILED)
                    enrichment.failed(request.documentId(), request.newsVersion(), safeError(e));
            } catch (Exception persistenceFailure) {
                log.error("Cannot persist research failure for {} ({})", id, persistenceFailure.getClass().getSimpleName());
            }
        } finally {
            if (request != null && request.conversationId() != null) try {
                chatbot.interruptTurn(store.get(id).owner(), request.conversationId(), request.turnId());
            } catch (Exception e) {
                log.error("Cannot reconcile chat turn for job {}", id, e);
            }
            if (progress != null) try {
                progress.close();
            } catch (Exception ignored) {
            }
            if (work != null) try {
                work.close();
            } catch (Exception e) {
                log.error("Cannot release research work lease", e);
            }
            if (timeout != null) timeout.cancel(false);
            tasks.remove(id);
            if (request != null && external(request)) activeNews.remove(request.documentId(), id);
            if (closing && tasks.isEmpty()) closeResources();
        }
    }

    private String safeError(Exception e) {
        // Provider exception bodies can contain full prompts or URLs. Keep persisted/displayed failures bounded and generic.
        if (e instanceof ResearchFailure) return "Answer withheld: " + ResearchFailure.classify(e).name();
        return e instanceof IllegalArgumentException ? "Invalid research input or model configuration"
                : "Research failed (" + e.getClass().getSimpleName() + "); check document readability, model availability and configured limits";
    }

    private Path confinedFile(String filename) throws IOException {
        if (filename == null || !filename.matches("[a-f0-9-]{36}\\.pdf"))
            throw new IllegalArgumentException("Expected an uploaded archive file");
        Path path = archive().resolve(filename);
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) throw new IOException("Archived PDF not found");
        return path;
    }

    private Path download(String source, String id) throws Exception {
        return SecureDownloads.download(source, archive().resolve(id + ".pdf"), properties.limits().maxFileBytes());
    }

    public Job cancel(String id, String owner) throws IOException {
        get(id, owner);
        cancelInternal(id, "Cancelled by user");
        return get(id, owner);
    }

    private void cancelInternal(String id, String reason) {
        try {
            Job existing = store.get(id);
            if (existing.terminal()) return;
            update(id, State.CANCELLED, null, reason);
            if (existing.request().conversationId() != null)
                chatbot.interruptTurn(existing.owner(), existing.request().conversationId(), existing.request().turnId());
            var task = tasks.get(id);
            if (task != null) {
                task.cancel(true);
                if (worker.remove(task)) {
                    tasks.remove(id);
                    if (external(existing.request())) activeNews.remove(existing.request().documentId(), id);
                }
            }
            if (external(existing.request()))
                enrichment.failed(existing.request().documentId(), existing.request().newsVersion(), reason);
        } catch (Exception e) {
            log.error("Research cancellation persistence failed for {}", id, e);
        }
    }

    private Job update(String id, State state, Research.Answer answer, String message) throws IOException {
        Job previous = store.get(id);
        if (previous.state() == state && answer == null && message == null) return previous;
        Job job = store.update(id, state, answer, message);
        var callbacks = listeners.get(id);
        if (callbacks != null) for (var callback : callbacks)
            try {
                callback.accept(job);
            } catch (RuntimeException ignored) {
                callbacks.remove(callback);
            }
        return job;
    }

    public AutoCloseable subscribe(String id, String owner, Consumer<Job> callback) throws IOException {
        get(id, owner);
        var callbacks = listeners.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>());
        if (callbacks.size() >= 4) throw new IllegalStateException("Too many research event subscriptions");
        callbacks.add(callback);
        callback.accept(get(id, owner));
        return () -> {
            callbacks.remove(callback);
            if (callbacks.isEmpty()) listeners.remove(id, callbacks);
        };
    }

    public void close() throws Exception {
        closing = true;
        for (String id : List.copyOf(tasks.keySet())) cancelInternal(id, "Client shutdown");
        worker.shutdownNow();
        deadlines.shutdownNow();
        if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Research worker still stopping; exclusive resource ownership retained until it exits");
            observer.event("lifecycle", "INTERRUPTED", 1);
            return;
        }
        closeResources();
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        try {
            model.close();
            store.close();
        } catch (Exception failure) {
            log.error("Research resource close failed ({})", failure.getClass().getSimpleName());
        }
    }

    public Conversation createConversation(String owner) throws Exception {
        return value(chatbot.createConversation(owner));
    }

    public List<Conversation> listConversations(String owner) throws Exception {
        return chatbot.listConversations(owner).stream().map(ResearchClient::value).toList();
    }

    public Conversation conversation(String owner, String id) throws Exception {
        return value(chatbot.conversation(owner, id));
    }

    public List<ResearchChatbot.Message> history(String owner, String id) throws Exception {
        return chatbot.history(owner, id);
    }

    public List<ResearchChatbot.Report> listDocuments() throws Exception {
        return chatbot.listDocuments();
    }

    public Path originalDocument(String id) throws Exception {
        return Path.of(chatbot.uploadedDocument(id).file());
    }

    public boolean shutdownFailed() {
        return model.shutdownFailed();
    }

    public void retryIdleShutdown() {
        model.retryIdleShutdown();
    }

    /**
     * Blocking convenience for standalone consumers. HTTP hosts return the job ID and use progress events.
     */
    public Job await(String id, String owner, Duration timeout) throws Exception {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive wait timeout required");
        var done = new CountDownLatch(1);
        try (var subscription = subscribe(id, owner, job -> {
            if (job.terminal()) done.countDown();
        })) {
            if (!done.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new TimeoutException("Research job is still processing");
            return get(id, owner);
        }
    }

    public enum State {QUEUED, STARTING_MODEL, PROCESSING, COMPLETED, NEEDS_CLARIFICATION, FAILED, CANCELLED, INTERRUPTED}

    /**
     * Application-specific optimistic writes. All callbacks must be bounded and preserve manual fields.
     */
    public interface Enrichment {
        default ExternalDocument resolve(String reference) throws Exception {
            throw new UnsupportedOperationException("External document resolver not configured");
        }

        default boolean completed(String key, String version, String summary, String fingerprint) throws Exception {
            return true;
        }

        default void failed(String key, String version, String reason) throws Exception {
        }
    }

    /**
     * Credentials and provider-specific operations stay in the host adapter.
     */
    public interface ManagedResource {
        default String identity() {
            return "unmanaged";
        }

        default boolean isRunning() throws Exception {
            return true;
        }

        default void start() throws Exception {
        }

        default void awaitReady() throws Exception {
        }

        default void stop() throws Exception {
        }
    }

    /**
     * Version-one job wire format retained for existing snapshots and clients. newsId is legacy-only;
     * external enrichment uses documentId as its generic source key and newsVersion as its version token.
     */
    public record Request(String kind, String company, List<String> periods, String question,
                          String documentId, String file, String source, Long newsId, String newsVersion,
                          String conversationId, String turnId) {
        public Request(String kind, String company, List<String> periods, String question, String documentId,
                       String file, String source, Long newsId, String newsVersion) {
            this(kind, company, periods, question, documentId, file, source, newsId, newsVersion, null, null);
        }

        public Request {
            periods = periods == null ? List.of() : List.copyOf(periods);
        }
    }

    public record Job(String id, String owner, Request request, State state, String createdAt,
                      String updatedAt, Research.Answer answer, String error) {
        public Job state(State next, Research.Answer result, String message) {
            return new Job(id, owner, request, next, createdAt, Instant.now().toString(), result, message);
        }

        public boolean terminal() {
            return Set.of(State.COMPLETED, State.NEEDS_CLARIFICATION, State.FAILED, State.CANCELLED, State.INTERRUPTED).contains(state);
        }
    }

    public record Conversation(String id, String title, String activeTurn, String scope, String pendingDocument,
                               String updatedAt) {
    }

    public record ExternalDocument(String key, String company, String period, String source, String version) {
        public ExternalDocument {
            Research.required(key, "document key");
            Research.required(company, "company");
            Research.required(period, "period");
            Research.required(source, "source");
            Research.required(version, "source version");
        }
    }

    public record Options(Path directory, String schema, int embeddingDimensions, Research.Settings limits,
                          int queueCapacity,
                          Duration jobTimeout, Duration idleGrace, boolean manageResource) {
        public Options {
            Objects.requireNonNull(directory);
            Objects.requireNonNull(limits);
            Objects.requireNonNull(jobTimeout);
            Objects.requireNonNull(idleGrace);
            ResearchSchema.identifier(schema);
            if (embeddingDimensions < 1 || embeddingDimensions > 2000 || queueCapacity < 1 || queueCapacity > 100 || jobTimeout.isNegative() || jobTimeout.isZero() || idleGrace.isNegative())
                throw new IllegalArgumentException("Invalid runtime limits");
        }

        public static Options defaults() {
            return new Options(Path.of("research-data"), "document_research", 768, Research.Settings.defaults(), 8, Duration.ofMinutes(30), Duration.ofSeconds(60), false);
        }
    }

    public static final class Builder {
        private final DataSource source;
        private final ModelGateway model;
        private final DocumentExtractor extractor;
        private Options options = Options.defaults();
        private ToolRegistry tools = new ToolRegistry();
        private Function<Set<String>, Map<String, String>> aliases = companies -> Map.of();
        private Function<String, String> sourceLocator = id -> "upload:" + id;
        private ManagedResource resource = new ManagedResource() {
        };
        private Enrichment enrichment = new Enrichment() {
        };
        private ResearchObserver observer = ResearchObserver.NONE;
        private int perOwnerCapacity = 2;

        private Builder(DataSource source, ModelGateway model, DocumentExtractor extractor) {
            this.source = Objects.requireNonNull(source);
            this.model = Objects.requireNonNull(model);
            this.extractor = Objects.requireNonNull(extractor);
        }

        public Builder perOwnerCapacity(int value) {
            if (value < 1 || value > 100) throw new IllegalArgumentException("Invalid per-owner capacity");
            perOwnerCapacity = value;
            return this;
        }

        public Builder options(Options value) {
            options = Objects.requireNonNull(value);
            return this;
        }

        public Builder tools(ToolRegistry value) {
            tools = Objects.requireNonNull(value);
            return this;
        }

        public Builder aliases(Function<Set<String>, Map<String, String>> value) {
            aliases = Objects.requireNonNull(value);
            return this;
        }

        public Builder sourceLocator(Function<String, String> value) {
            sourceLocator = Objects.requireNonNull(value);
            return this;
        }

        public Builder managedResource(ManagedResource value) {
            resource = Objects.requireNonNull(value);
            return this;
        }

        public Builder enrichment(Enrichment value) {
            enrichment = Objects.requireNonNull(value);
            return this;
        }

        public Builder observer(ResearchObserver value) {
            observer = Objects.requireNonNull(value);
            return this;
        }

        public ResearchClient build() throws Exception {
            return new ResearchClient(this);
        }
    }

}

