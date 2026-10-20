# From a user message to a grounded answer

This is the implemented call flow, not a proposed architecture. Paths below are relative to this repository's root.
The core is plain Java. Spring HTTP/SSE and the browser widget are optional adapters. Authentication is host-owned.

## Prerequisite document path

A question searches uploaded reports that have already reached `READY`. It does not choose arbitrary files to embed.

1. `ResearchWebController.upload()` checks the authenticated conversation, capacity, PDF header and size, archives the
   upload, then calls `ResearchClient.submitUpload()`.
2. `ResearchChatbot.reserveUpload()` reserves an owned turn in PostgreSQL. `ResearchClient.submitTurn()`/`submit()`
   writes
   a durable job snapshot and queues work. The controller returns HTTP 202 with a job ID.
3. The single worker calls `ResearchClient.run()` → `ResearchChatbot.processTurn()` → `upload()`.
4. `upload()` fingerprints the file and skips an unchanged indexed duplicate. Otherwise `ResearchEngine.chunks()` calls
   `DoclingExtractor.chunks()`, which uses the official Java SDK's `chunkSourceWithHybridChunker()` operation. Docling
   supplies structure-aware, tokenizer-aware chunks, contextual embedding text, raw excerpts, headings and page
   provenance.
5. `completeMetadata()` calls `model.structured(DOCUMENT_METADATA, ...)`. Java checks issuer/date evidence. Ambiguity
   retains the draft and requests clarification. A later user message completes metadata instead of starting research.
6. `ResearchEngine.publish()` embeds each chunk's embedding text and validates vectors before opening the publication
   transaction. `PostgresResearchStore.publish()` commits the full version, raw/contextual text and vectors atomically.
   Failure preserves any previous usable version; extraction/model errors do not produce a ready replacement.

The default document embedding profile is `embeddinggemma`, 768 dimensions. The vectors are stored in
`chunks.embedding vector(768)`. PostgreSQL also stores document metadata, active versions and conversation turns.
Original PDFs and durable job snapshots use the configured local directory. Ollama supplies inference, not index
storage.

## Entry and asynchronous boundary

Example initial question: “What revenue did ACME report for the quarter ending September 2025?”

- **Browser:** `chat-widget.js.send()` reads the textarea and posts `{text, context}` to
  `/api/research/conversations/{id}/messages`. The widget's `initialize()` has already obtained CSRF configuration and
  selected/created a private conversation. Optional context contains validated uploaded-document IDs, not credentials.
- **HTTP:** `ResearchWebController.message()` derives owner identity from the authenticated `Principal` and calls
  `ResearchClient.submitMessage(owner, conversationId, text, documentIds)`. Host security filters handle authentication
  and CSRF before this method. The browser cannot select an arbitrary owner.
- **Admission:** `submitMessage()` checks global/per-owner capacity and calls `ResearchChatbot.reserveMessage()`.
  `PostgresResearchStore.reserve()` checks ownership and serializes the active-turn reservation. A second active turn is
  rejected rather than racing the first one.
- **Job:** `submitTurn()` calls `submit()`, which persists a `QUEUED` snapshot and submits a task to the one-worker
  executor.
  It captures trace context for the worker. The controller returns 202 immediately after admission; inference is not
  performed on the HTTP request thread. Capacity errors return 429; a busy conversation returns 409.
- **Worker:** `run()` holds a model-work lease, starts the job deadline/progress callback and calls
  `ResearchChatbot.processTurn(owner, conversationId, turnId)`. The lease protects active work; it does not eagerly
  start
  an idle model machine. In managed mode, the first actual model call acquires readiness through `ResearchModelSession`.

## Planning and conversational follow ups

`processTurn()` checks the current owned active turn, loads its payload, and chooses upload, metadata clarification or
`research()`. Normal research proceeds as follows:

1. `research()` loads `READY` uploaded reports, restricted by optional selected-document context. An empty corpus
   returns
   `INSUFFICIENT_EVIDENCE` without model inference. Telegram enrichment documents are excluded.
2. It loads read-only aliases, previous validated scope and the latest four compact completed turns, within the existing
   8,000-character history cap. PostgreSQL remains the sole conversation store.
3. `structured(QUERY_SCOPE, ...)` makes **one planning call**, through `ResearchModelSession.structured()` →
   `OllamaGateway.structured()` → stateless native AI Services guardrails. The response contract is:

   ```json
   {
     "targets": [{"company":"ACME","period":"2025-07-01/2025-09-30"}],
     "clarification": "",
     "standaloneQuestion": "What revenue did ACME report for the quarter ending September 2025?"
   }
   ```

4. Java validates company catalog membership, dates and scope bounds (two companies/four periods). A request for the
   last
   two/three/four quarters receives the deterministic consecutive-quarter guard. Required missing reports produce
   `INSUFFICIENT_EVIDENCE`; ambiguous intent produces `CLARIFICATION_REQUIRED`.
5. `standaloneQuestion` resolves omitted subjects/metrics from history. For a subsequent “Why did it change?”, the
   planner
   must identify the previously discussed metric or clarify. The resolved question—not the ambiguous literal message—is
   used for query embedding, lexical retrieval, tool selection and final generation. Original user text stays in
   history.
   Empty/malformed resolved questions fail before retrieval. The rewrite adds no extra planning call.

This uses model-based interpretation, not a guarantee that every follow-up is understood. Previous assistant claims are
untrusted context and do not become financial evidence. Rewriting behavior needs real-model evaluation alongside
retrieval.

## Retrieval and augmentation

`research()` selects a bounded tool shortlist with `ToolRegistry.select(standaloneQuestion)` and calls
`ResearchEngine.ask(scope, standaloneQuestion, tools)`.

1. `ask()` calls `search(scope, question)`.
2. `search()` calls `model.embed(question)` once, through the model-session wrapper and `OllamaGateway.embed()`.
   LangChain4j invokes Ollama's embedding endpoint. This is a **query embedding**; no document is re-embedded here.
3. For each target, `PostgresResearchStore.search(target, query, vector, topK)` filters SQL by `UPLOAD`, canonical
   company,
   reporting interval and active document version. Parameters are bound; model output never becomes executable SQL.
4. Lexical search matches `plainto_tsquery('english', query)` against `search_text` and orders by `ts_rank_cd`.
   Vector search orders by `embedding <=> queryVector`, pgvector cosine distance. This is not BM25 or Lucene.
5. RRF combines both lists using `sum(1 / (60 + rank))`. The top seed results are expanded with adjacent chunks from the
   same document version and section. Duplicate evidence IDs are removed.
6. `ask()` verifies that every requested target has retrieved evidence. `balanced()` selects evidence across targets
   under the character budget. A nonempty result from one quarter cannot silently stand in for another missing quarter.
7. `generate()` constructs system instructions plus the resolved question, validated scope and serialized evidence.
   **Retrieving that evidence and adding it to the model request is retrieval-augmented generation.**

HNSW/GIN indexes support vector/text queries; planner choices and filter-heavy recall require native PostgreSQL testing.
Rank fusion is not a learned reranker. The default seed top-k is eight; context selection still applies after expansion.

## Generation tools and grounding checks

`ResearchEngine.generate()` calls `ResearchModelSession.generate()` → `OllamaGateway.generate()`.

- `NativeGuardrails.run()` validates the stateless AI Services input/output boundary. The internal gateway constructs
  LangChain4j tool specifications from the portable `Research.Tool` interface. `@Tool` annotations are not required.
- The gateway offers at most eight selected tools. If the model requests a tool, Java validates its registration,
  arguments and company/period scope **before execution**. `FinancialTools` reads host-provided verified snapshots and
  computes comparable changes with `BigDecimal`, preserving units, currency, basis, periods, timestamps and sources.
- Bounded tool evidence/facts are appended as tool messages. The model is called again to produce final JSON claims.
  There are at most four tool executions across two tool rounds and at most three generation calls. Tools are optional;
  the loop ends immediately when the model returns a final response. There are no automatic retry/reprompt loops.
- The final draft reaches `AnswerValidator.validate()`: strict schema, known citation IDs, original quoted facts,
  scoped Java tool facts, numerical placeholders and Java-rendered quantities are checked. A number appearing somewhere
  in a passage does not establish the metric claimed by the answer.
- For nonempty claims, one tool-free `structured(VERIFICATION, ...)` call receives claims and their cited evidence.
  Every claim needs a complete structured supported verdict. Unsupported/missing verdicts, invalid JSON, truncation or
  verifier outage withhold the answer. Empty claims return insufficient evidence without this verifier call.

Native schema checks, deterministic policy and a fallible semantic verifier serve different purposes. An accepted
validation report records the policy outcome; it is not proof of factual accuracy or source authenticity.

## Persistence progress and display

`processTurn()` calls `PostgresResearchStore.finish()` to persist the private result and clear the active turn. Accepted
answers include claims, citations, supporting excerpts and the validation report. Failures persist a safe failure code
without an unchecked draft. Cancellation/restart paths prevent stale work from accepting a late result.

`ResearchClient.run()` publishes the terminal job state. `ResearchWebController.events()` adapts
`ResearchClient.subscribe()` to SSE. Widget `watch()` receives the terminal event, closes the stream, then calls
`loadList()`/`reload()` to fetch persisted history. `render()` uses text-safe DOM operations and builds expandable
citations
and authenticated original-PDF links. SSE carries status/completion, not unchecked answer tokens.

An accepted job can finish with clarification or insufficient evidence. Always inspect the conversation result rather
than equating `COMPLETED` with `ANSWERED`. Model-work leases release in the worker's finally path. Shared-host mode
disables VM shutdown; a separately managed owned model VM uses the configured idle grace after active work finishes.

## Source navigation

- `widget/src/main/resources/META-INF/resources/document-research/chat-widget.js`: `initialize`, `send`, `watch`,
  `reload`, `render`.
- `spring-boot/src/main/java/docrag/spring/ResearchWebController.java`: `message`, `upload`, `events`.
- `core/src/main/java/docrag/ResearchClient.java`: `submitMessage`, `submitTurn`, `submit`, `run`, `subscribe`.
- `core/src/main/java/docrag/ResearchChatbot.java`: `reserveMessage`, `processTurn`, `research`, `structured`,
  `completeMetadata`.
- `core/src/main/java/docrag/ResearchEngine.java`: `publish`, `ask`, `search`, `balanced`, `generate`, `scoped`.
- `core/src/main/java/docrag/PostgresResearchStore.java`: `reserve`, `search`, `publish`, `finish`.
- `core/src/main/java/docrag/OllamaGateway.java`: `embed`, `structured`, both `generate` methods;
  `NativeGuardrails.java`: `run`.
- `core/src/main/java/docrag/AnswerValidator.java`: `validate`; `FinancialTools.java`: read-only metric/comparison
  tools.

The [README installation](../README.md#installation-from-a-clean-checkout) explains setup;
[operations](OPERATIONS.md#browser-widget-and-host-integration) covers launcher diagnostics.
The README's two diagrams show component ownership and the worked flow; this page supplies the actual method detail.
