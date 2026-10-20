# Integration and operations

[Quickstart](../README.md) · [Message method flow](MESSAGE-FLOW.md) · [Security](../SECURITY.md)

Commands run from the library repository root. Use explicitly configured isolated services for tests.

## Design choices

- PostgreSQL holds document versions, text, vectors and conversation state, allowing atomic publication without a
  second index lifecycle. Full-text ranking is PostgreSQL rank, not BM25. Embedding profiles need compatible schemas.
- Docling supplies structure/token-aware chunks. Preserve raw excerpts separately from embedding context and reject
  partial coverage. SDK 0.7.0 does not expose the repeated-table-header switch; evaluate pinned backend/table behavior.
- Uploaded evidence is shared; conversation operations check owner identity supplied by the authenticated host.
  Multi-tenant corpus isolation requires a separate access design.
- The library owns durable jobs, the archive and model-session coordination; the host supplies data/provider callbacks.
  Run one instance per schema/job directory. Interrupted jobs require explicit retry; distributed claiming is not
  implemented.
- Credentials stay in the host. Shared-host mode disables managed VM shutdown; separate managed infrastructure stops
  only when owned and idle. Telegram enrichment remains outside the upload corpus and applies source-version checks.
- Preserve historical rows and archives without a runtime Lucene fallback. Stored `TELEGRAM`/`NEWS` discriminators
  remain compatible for external enrichment adapters.
- Custom `ModelGateway` implementations must support `VERIFICATION` and return
  `{targets, clarification, standaloneQuestion}` for `QUERY_SCOPE`. Research rewrites are nonblank, capped at 2,000
  characters; they may be blank for clarification. PostgreSQL is the sole conversation store.

## Tools: reusable capabilities, host adapters, and `@Tool`

`FinancialTools` belongs to the core library. Its `DataSource` interface supplies stored `Snapshot` facts. A host
adapter
reads existing records and maps company aliases. `get_financial_metrics` and
`compare_financial_metrics` preserve source, as-of time, reporting basis, currency, per-metric units and missing values.
Calculations use Java `BigDecimal`; comparisons require matching known units/currency/basis and compatible periods.
Percent changes are omitted for zero or negative baselines.

```java
FinancialTools.DataSource storedFacts = (company, period) -> null; // Replace with your read-only store adapter.
ToolRegistry registry = ToolRegistry.financial(storedFacts);
// registry.register(yourMergerEvidenceTool, java.util.Set.of("merger", "acquisition"));
// registry.register(yourQipEvidenceTool, java.util.Set.of("qip", "dilution", "placement"));
```

Contributors implement the provider-independent `Research.Tool` SPI and register trusted read-only capabilities during
initialization. `ToolRegistry` accepts up to 1,000 tools but selects at most eight using explicit keywords; LangChain4j
receives only that shortlist. Tool names, argument schemas, company/period scope, output sizes and execution rounds are
checked. There are at most four tool calls across two tool rounds, followed by a final response. Current tool scope
requires company and reporting-period arguments; event-specific tools need a deliberate extension of that validation.
Merger and QIP implementations are **future contributions**, not existing analytical capabilities.

More tools broaden coverage; irrelevant or overlapping tools can increase latency, token use and selection errors.
The current keyword selector is intentionally simple and bounded, not an evaluated semantic tool router. Add domains
with documented input/output contracts, provenance, ambiguity handling and evaluations before expanding selection.

Why no `@Tool` annotation? LangChain4j supports both
[annotated tools and explicit low-level tool specifications](https://docs.langchain4j.dev/tutorials/tools/).
`OllamaGateway.generate` translates the portable SPI into real LangChain4j `ToolSpecification` objects and executes
native
model tool requests. This is genuine tool calling. An annotation is a convenient discovery mechanism, not a requirement
or a quality indicator; keeping it inside an optional provider adapter avoids binding public library types to
LangChain4j.

## Browser widget and host integration

Serve `widget/src/main/resources/META-INF/resources/document-research/chat-widget.js` as an ES module, or depend on its
resource JAR in a compatible server. The host must expose authenticated same-origin endpoints and its CSRF token.

```html

<script type="module">
    import {mount} from '/document-research/chat-widget.js';

    const chat = mount({apiBase: '/api/research', title: 'Company research'});
    // chat.open(); chat.close(); chat.setContext({documentIds:['an-uploaded-document-id']}); chat.destroy();
</script>
```

The Web Component shows a bottom-right launcher, mobile panel, PDF attachments, history, progress, retry/cancel
controls,
clarification replies and expandable citations. It supports keyboard submission, Escape, focus restoration and
accessible
labels. Model content is rendered with `textContent`. Browser storage holds only the conversation ID under a per-user
key.
SSE carries job stages and final completion, not token streaming. Reopen/reload reconnects interrupted progress without
polling. CSS custom properties `--research-accent`, `--research-surface` and `--research-text` control styling.

The Boot host mounts it through shared template fragments, replacing the separate dashboard (`/research` redirects).
Its API contract is:

- `GET /api/research/chat/config`: user-specific storage key, CSRF header/token, upload limit and shared-corpus notice.
- `GET/POST /api/research/conversations`; `GET /conversations/{id}`: owned conversations/history/active job.
- `POST /conversations/{id}/messages`: `{text, context?:{documentIds:[]}}`; `POST /conversations/{id}/documents`:
  multipart PDF.
  Both return HTTP 202 and a job ID immediately after durable reservation/queueing.
- `POST /conversations/{id}/turns/{turn}/retry`; `GET /jobs/{id}`; `GET /jobs/{id}/events`; `POST /jobs/{id}/cancel`.
- `GET /documents`; authenticated `GET /documents/{id}/file` provides original uploaded PDFs for cited-page inspection.
- `POST /news/{id}/retry`: host-only Telegram enrichment path; Telegram documents stay outside the uploaded research
  corpus.

All abbreviated paths above are under `/api/research`. The optional library Spring module owns HTTP/SSE adaptation and
widget resources; the core client owns jobs, archive handling and model-session coordination. The host owns
authentication,
repositories, provider callbacks, metrics export and credentials. No model URL or infrastructure credential is sent to
the
widget. Documents/tool outputs/history remain untrusted evidence. Shared uploads must suit all authenticated users.

Host settings (research stays disabled by default):

```properties
research.enabled=false
research.initialize-schema=false
research.schema=document_research
research.embedding-dimensions=768
research.ollama-url=http://localhost:11434
research.chat-model=qwen2.5:14b
research.embedding-model=embeddinggemma
research.docling-url=http://localhost:55001
research.docling-version=docling-serve-v1.30.0
research.tokenizer=google/embeddinggemma-300m
research.chunk-tokens=512
research.queue-capacity=8
research.job-timeout=30m
research.manage-vm=false
research.idle-grace=60s
```

The application supplies its existing `DataSource`; provision the separate research schema explicitly before enabling.
The `RESEARCH_JDBC_URL` variables in the standalone example do not configure that host DataSource. Initialize the schema
on the **same database/search path** that the host uses, with the same embedding identity and dimension.

To use the Compose Docling service with a configured host, override `RESEARCH_DOCLING_URL` to
`http://localhost:55001` and `RESEARCH_DOCLING_VERSION` to `docling-serve-v1.30.0`. Then enable `research.enabled=true`
(or `RESEARCH_ENABLED=true`) in that host's local/deployment configuration, rebuild/restart it, sign in and reload an
authenticated
page. Preserve existing site-specific database/model endpoints. No library build starts or reconfigures the website.

The launcher is intentionally omitted when research is disabled or the page is anonymous. Diagnose mounting in this
order:

1. Authenticated HTML should include `<script type="module" src="/js/research-chat-loader.js">` through the shared
   sidebar/page fragment. The optional library `ResearchChatViewAdvice` must be imported.
2. `/js/research-chat-loader.js` and `/document-research/chat-widget.js` should return JavaScript, not a login page or
   404.
   Both resource JARs must be included in the application's rebuilt/redeployed artifact.
3. A `<document-research-chat>` element should exist directly under `document.body`; the launcher should remain visible
   even when the sidebar is closed. Clicking it loads `/api/research/chat/config`, which should return authenticated
   configuration JSON. No model request is needed just to show/open the launcher.
4. Upload a PDF and wait for `DOCUMENT_READY` before asking a research question. An empty corpus correctly returns
   `INSUFFICIENT_EVIDENCE`. Read the persisted conversation result after a completed job; completion is not proof of an
   answer.

The provided loader assumes the application is hosted at the origin root. For a custom context path, mount the widget
with that same-origin API prefix and serve/import the module under that context path instead of using the root loader.

For stored financial comparisons configure verified `research.financial-basis[COMPANY@YYYY-MM-DD/YYYY-MM-DD]`
entries with `.currency`, `.units`, `.basis`. Unknown values block comparisons.
Managed VM operation additionally requires `research.project`, `.zone`, `.instance` and host application-default
credentials. One worker, a bounded queue and one VM owner coordinate all new model work. An active job pins an owned VM;
first model use starts it when needed, and the default idle grace is 60 seconds after work releases it. A VM already
running without the ownership marker is not stopped. Failed stops retain ownership for explicit retry/restart.
Interrupted jobs require explicit retry; they are not automatically replayed after a crash.

## Guardrails

The implementation uses LangChain4j 1.22.0 native input/output guardrails on stateless AI Services. The bounded tool
orchestrator runs inside that boundary; authorization is checked before execution, because output guardrails run
after tools. No framework chat memory, automatic retry, reprompt loop, fallback answer, or Spring AI dependency is used.
Framework guardrails are experimental APIs, not a certification.
See [upstream semantics](https://raw.githubusercontent.com/langchain4j/langchain4j/1.22.0/docs/docs/tutorials/guardrails.md).

### Answer contract

Generation returns `claims` and optionally `facts`. Each claim has `text` and `citations`. Financial quantities in text
must use fact placeholders, such as `Revenue is {{F1}}.` A document fact supplies `id`, `evidenceId`, `metric`, decimal
`value`, `currency`, `unit`, `basis`, and an exact `quote`. Quotes must contain the metric, number, currency and units;
scope and source come from the cited document, not model-selected companies or URLs. `NOT_STATED` preserves an unknown
basis; unsupported currency or units must not be guessed. Java renders the quantity. Document facts are stated values,
never model-calculated differences. Java financial tools provide separately scoped facts, referenced as `{{T1_F1}}`.

The verifier receives only the proposed claims and cited evidence. It has no tools and must return one structured
SUPPORTED/UNSUPPORTED verdict per claim ID. Missing/duplicate verdicts, unsupported claims, malformed responses,
timeouts and unavailable verifiers withhold the entire answer. No unchecked draft is persisted in conversation results.
Checking a quote's existence alone does not establish metric association; that remains a verifier/evaluation concern.
Counterfeit or misleading uploaded reports cannot be authenticated by a language model. Uploads remain shared with
authenticated users; source authenticity and upload permissions remain host policy.

Accepted answers carry `validation={policyVersion,accepted,checkedClaims,code}`. Older persisted answers have no such
report and are labelled historical/unverified. Failed turns carry a stable `failureCode`; insufficiency remains a
separate outcome. Existing JSON constructors and optional fields preserve old stored records. Custom `ModelGateway`
implementations must implement `Structure.VERIFICATION`; absence fails closed. Fact validation is deliberately
conservative: it can withhold valid quantities with incomplete table headers, unsupported date formats or unstated
units.

One nonempty generated answer costs at most one extra verifier call. A long summary uses that policy for each bounded
map/reduce generation; its cost grows with the number of chunks. Empty claims abstain without a verifier call. The
same-model verifier can share the generator's mistakes; do not substitute its pass rate for reviewed factual accuracy.

## CPU deployment

The existing `research.chat-model` remains unchanged. To benchmark the larger model explicitly, activate
`research-cpu30b`; it selects `qwen3:30b`, 8192 context tokens, 2048 output tokens and four inference threads, without
enabling research or downloading weights. The [official model](https://ollama.com/library/qwen3:30b) documents Q4_K_M
weights around 19 GB. Record the actual digest/quantization with `ModelDiagnostics`; an alias can change.

Additional properties: `research.verifier-model` (blank reuses generation), `research.model-context-tokens`,
`research.model-output-tokens`, `research.model-cpu-threads`, `research.model-thinking`, `research.per-owner-capacity`
(default two), and `research.shared-host` (default true). Shared-host mode rejects `research.manage-vm=true`.
One worker, eight queued jobs and one active turn per conversation remain the defaults. Excess admission returns
HTTP 429 with `code=RESOURCE_LIMIT` and `Retry-After: 30`. This is pending-job admission, not a distributed rate
limiter.

Keep only one large chat model plus the embedding model in the memory budget. A different verifier model can cause
reloads and excessive memory use; benchmark before enabling it. Generation-model changes do not change the embedding
profile or require re-ingestion. Changed embedding profiles still require explicit compatible storage/re-ingestion.
Request character limits are not exact tokenization; the provider context/output limits and rejection of truncated
responses remain mandatory. Never claim that fitting weights in 40 GB establishes acceptable latency or scheduler
safety.

`ModelDiagnostics` adds capabilities, digest, quantization, provider runtime memory, CPU count, token usage, loading
time and throughput. Missing provider metadata is `UNAVAILABLE`; process peak RSS still needs external measurement.
`EvaluationRunner` records phase time and model usage; human support/numerical judgments remain pending until reviewed.

## Optional monitoring stack

Build libraries in README order. Package the standalone example with
`mvn -f examples/spring-boot/pom.xml -Presearch-observability package`. That opt-in Maven
profile adds Boot's OpenTelemetry starter and OTLP metrics registry. Run with Spring profiles
`research-observability` and, only for a larger-model benchmark, `research-cpu30b`. Without the Maven profile, no SDK or
telemetry exporter is installed automatically; basic Micrometer measurements still work. Export URLs/timeouts can be
overridden using normal host configuration. Existing authentication and Actuator exposure remain unchanged.

Set `GRAFANA_ADMIN_PASSWORD` to a local monitoring password and explicitly run:

```sh
docker compose -f observability/deploy/compose.yaml up -d
```

Grafana: `http://localhost:13000` (admin and the supplied password). Prometheus: `http://localhost:19090`.
Collector OTLP/HTTP: `http://localhost:14318`. Grafana has provisioned Prometheus, Tempo and Loki data sources,
correlated
trace/log links, and a Research Runtime dashboard. Metrics reach Prometheus through the Collector, so the application
does not need to expose an unauthenticated metrics endpoint. Alert rules are local Prometheus rules; external alert
delivery is not configured. The 180-second latency warning is an initial operational threshold, not a measured SLO.

Traces use bounded batches/queues, short export deadlines, and allowlisted metadata. Prompts, documents, answers, tool
payloads, identity and provider exception bodies are not captured by the research adapter. Job IDs are trace-only
attributes, never metric labels. Exporter failures are isolated from processing. Do not enable SDK HTTP body logging.
The Collector additionally strips unknown span/event attributes. This does not establish privacy for unrelated host
instrumentation when exporting directly to another backend; apply equivalent policies there.

For logs, enable ECS structured file logging and set `LOGGING_FILE_NAME` to an absolute path ending in `.json` inside
`observability/deploy/logs/`. The Collector tails new records, keeps only the research telemetry logger, and exports
only operation, outcome, trace ID and span ID. It never forwards arbitrary application log messages. Logs are ignored
by Git. Stop normally with `docker compose ... down`; do not use `down -v` unless intentionally discarding monitoring
data.

The bundled local monitoring stack has 24-hour retention, bounded memory/CPU, no public ports and ephemeral Tempo/Loki
data.
It is never started automatically. Prefer a separate monitoring host for production; remote export requires TLS,
authentication, appropriate retention/backups, and equivalent redaction. The shared-host profile is not a capacity
guarantee.

## Extraction and downloads

The external-enrichment downloader uses Apache HttpClient 5 with a validating DNS resolver, so the actual connection
uses validated addresses. Redirects and literal IPs are checked; private/local/reserved destinations are rejected.
No URL from a question or model output is fetched. Original PDFs and historical archives are never automatically
deleted.

`compose.docling-isolated.yaml` is an explicit override for the local Docling service: restricted network, read-only
filesystem, dropped capabilities, bounded threads/processes/memory, and temporary storage. Pre-provision its models
and tokenizer before disabling outbound access. Validate cache paths and extraction on the target image; this override
does not prove parser sandboxing or OCR quality. Do not apply it to production without deployment review.

## Verification, evaluation and adoption limits

```sh
# Set RESEARCH_TEST_JDBC_URL, RESEARCH_TEST_DB_USER and RESEARCH_TEST_DB_PASSWORD to an ISOLATED pgvector database.
./mvnw -o clean verify
cd widget
npm ci --ignore-scripts
npx puppeteer browsers install chrome
npm test
```

Database tests require PostgreSQL/pgvector and skip when the test URL is absent. CI explicitly provisions a native
pgvector container; there is no H2 substitute for research SQL. Browser tests use only their local stub server.
Docling and Ollama transport tests use loopback fixtures; they do not establish real OCR/model quality.

For opt-in real evaluation, copy dependencies for both core and Docling projects, then run the compiled classes below
with the module classes and dependencies on the classpath. POSIX classpath separators are `:`; Windows uses `;`.
The core classes provide `EvaluationFixtures` and `ModelDiagnostics`; the Docling module provides the other runners.

```text
docrag.example.DatabaseSettings initialize
docrag.example.EvaluationFixtures <fixture-directory>
docrag.example.ModelDiagnostics http://localhost:11434 qwen2.5:14b embeddinggemma
docrag.example.CompanyResearchExample <q1.pdf> <q2.pdf> "Compare Acme's last two quarters"
docrag.example.EvaluationRunner http://localhost:11434 qwen2.5:14b embeddinggemma <fixture-directory> <output-directory> all 180
docrag.example.ArchiveReindex <old-pdf-archive> 20
```

The generator writes ten fictional PDFs and fifty question specifications (30 development/20 held-out), including
follow-ups, peers, missing evidence and unsupported facts. These are **synthetic specifications awaiting real-corpus
human review**, not fifty reviewed real financial questions. The conversational runner records context document recall,
abstention agreement, failures, p50/p95 latency, process CPU and heap snapshots. Supported-answer and numerical
correctness
remain unscored until human review. For held-out follow-ups use the complete sequence or seed the explicitly identified
prior context. Use a fresh evaluation schema, record hardware/model/backend versions and include external-service
resource
measurements. Heap-after is not peak memory; local process CPU excludes database, OCR and model-server CPU.

Current bounds: 200 reports in the uploaded catalog; two companies/four periods per question; 50 MiB/500 pages/2,000
chunks per document; 32,000-character evidence context; eight selected tools/four calls; one host worker/eight queued
jobs.
Catalog planning can hit its 18,000-character limit earlier. Long summaries fail explicitly if all notes cannot fit;
there
is no silent coverage truncation. Blank/image-only pages may require preprocessing because missing extracted coverage is
rejected. Multi-page citations preserve all pages but do not map text offsets to individual page coordinates.
Natural-language date inference, OCR tables, model instruction resistance and factual support need real-model
evaluation.

Old Lucene files and original PDFs remain untouched. `ArchiveReindex` explicitly imports at most 100 selected archive
PDFs
through the new upload pipeline, stops on a metadata clarification, and prints its continuation conversation ID. It
reads
no Lucene index and performs no deletion. Restrict import directories to approved research reports: do not import
Telegram
archives into the shared upload corpus unintentionally. Host PDF links require PDFs inside its configured archive.

External enrichment stays outside the shared upload corpus and applies source-version checks. The library never
modifies a host's historical application migrations or tables. Disabled research does not invoke a legacy fallback.

Before expanding deployment, measure indexing/research alongside existing schedules on representative hardware:
conversion,
embeddings, full-text/vector SQL, model memory, queue latency and cloud idle behavior all consume resources. No
production
startup, native database concurrency, real-model accuracy or overlapping-server CPU-safety claim follows from unit
tests.
See [contribution guidance](../CONTRIBUTING.md) and [security policy](../SECURITY.md).

## Local HTTP simulation of the complete facade

The checked-in Postman mocks derive from the pinned SDK transport fixtures and contain synthetic data only. They do not
measure real model/OCR quality. No Postman login or cloud workspace is required.

```sh
postman simulation run postman/simulations/research.sim.yaml --port 55492 --no-history --output ndjson
# In another terminal, set RESEARCH_TEST_JDBC_URL/DB_USER/DB_PASSWORD for an isolated pgvector database,
# and RESEARCH_SIMULATION_URL=http://127.0.0.1:55492, then:
./mvnw -o -pl docling -am -Dtest=SimulatedClientTest -Dsurefire.failIfNoSpecifiedTests=false test
```

This opt-in check runs the real client, Docling Java SDK, LangChain4j/Ollama HTTP transports and PostgreSQL pipeline:
upload, metadata, embeddings, retrieval, cited answers, and an explicit model HTTP-503 failure. It skips when the
simulation/test database variables are absent. Run it separately from native CI database checks when no simulation is
started.

## Release preparation

The root `doc-rag-parent` reactor owns version 0.1.1 and common build/publishing configuration. Java packages are
`docrag.*`; `io.github.mgoyal05` is the Maven namespace only. Core is framework-independent. Adapters are optional;
the example is runnable but excluded from deployment. Existing schema, `research.*` properties, HTTP resource paths,
browser keys, job formats and Compose volume identity intentionally retain compatibility names.

Normal builds need no signing or publishing credentials:

```sh
./mvnw clean verify
./mvnw install
./mvnw -pl core -am clean verify
```

After the documented checks pass, validate/export sources with Python 3.10+:

```sh
python scripts/source-release.py
python scripts/source-release.py --output dist/doc-rag-0.1.1.zip
```

The exporter excludes build outputs, credentials and runtime data; rejects missing/empty release inputs and broken
local links; verifies ZIP CRC and every content hash; and writes `doc-rag-0.1.1.zip.sha256`. It refuses to overwrite an
existing archive. An archive checksum verifies transport integrity, not model quality or release authorization.
The archive prefix follows the parent POM version. ZIP timestamps use `SOURCE_DATE_EPOCH` when set, otherwise the
release commit timestamp (or the ZIP epoch when Git metadata is unavailable).

Windows uses `./mvnw.cmd`. Wrapper 3.3.4 downloads checksum-pinned Maven 3.9.11. Use `scripts/build` for an install
and `scripts/run-example` to launch the stable `examples/spring-boot/target/doc-rag-example.jar`.
On POSIX, `sh ./mvnw` also works when checkout executable bits are absent. Before creating the public tag, stage the
wrapper/shell scripts with executable modes (
`git update-index --chmod=+x mvnw scripts/build.sh scripts/run-example.sh`).
Source ZIP entries already carry these executable modes; this preparation preserves the existing Git index.
The validate phase rejects empty source, SQL, configuration, documentation, license and launch-script files.
The `-sources.jar` and `-javadoc.jar` artifacts are built normally. Widget's documentation JAR contains asset API
documentation rather than Java API pages; its sources JAR contains the original ES module.

After verifying namespace ownership and configuring an operator-held GPG key, the explicit command below creates a
**signed local bundle**. It is not part of normal builds and is not run automatically:

```sh
./mvnw -Prelease-bundle clean deploy
```

The Sonatype plugin is configured with `skipUpload=true` and `autoPublish=false`; the example is excluded. Inspect
`target/central-publishing/central-bundle.zip` and signatures before any separate, explicitly authorized upload.
Never put signing keys, passphrases or Portal tokens in this repository. Follow
[Central requirements](https://central.sonatype.org/publish/requirements/) and
[namespace verification](https://central.sonatype.org/register/namespace/). A prepared POM does not establish ownership,
signature validity or acceptance by Central. Enable/test the private security-reporting channel separately.

### Maven Central publishing

The namespace is `io.github.mgoyal05`. The release workflow publishes `doc-rag-parent` and the five library artifacts:
`doc-rag-core`, `doc-rag-docling`, `doc-rag-widget`, `doc-rag-observability`, and `doc-rag-spring-boot`.
The example application is built and tested but excluded from deployment. Ordinary builds still require no credentials.

One-time setup in GitHub repository Settings > Secrets and variables > Actions:

- Secret `CENTRAL_TOKEN_USERNAME`: the Sonatype Portal token username.
- Secret `CENTRAL_TOKEN_PASSWORD`: the Sonatype Portal token password.
- Secret `MAVEN_GPG_KEY`: the complete ASCII-armored private signing key, including BEGIN/END lines.
- Secret `MAVEN_GPG_PASSPHRASE`: the signing-key passphrase.
- Variable `MAVEN_GPG_KEY_FINGERPRINT`: the full 40-character RSA primary-key fingerprint, without spaces.

Use the verified namespace in [Central Portal](https://central.sonatype.com/), generate a
[publishing token](https://central.sonatype.org/publish/generate-portal-token/), and distribute the public signing key
through `hkps://keyserver.ubuntu.com` following the
[GPG instructions](https://central.sonatype.org/publish/requirements/gpg/). The workflow retrieves this public key
before signing and verifies every bundle signature against the configured fingerprint. The BC signer reads the private
key and passphrase only from environment variables. `actions/setup-java` generates Maven settings with server ID
`central` and environment references for the token; no settings or private keys belong in this repository.

For Windows Gpg4win, use `gpg` on PATH or the full executable path. List the keys with
`gpg --list-secret-keys --keyid-format long --fingerprint`; export the selected key to a secure location outside the
checkout using `gpg --armor --output <private-key-file> --export-secret-keys <fingerprint>`. Back up the key and
revocation certificate securely. Paste the exported key into the GitHub secret, never into an issue or committed file.

Before a release, commit and merge the intended changes into `main`, including any locally modified application code
that should ship. Update the parent version, all six child parent versions and release notes together. Do not reuse a
published version. Run these commands from the intended release commit (PowerShell uses `./mvnw.cmd`):

```sh
python scripts/source-release.py
python -m unittest discover -s scripts -p 'test_release.py' -v
python scripts/release.py --tag v0.1.1 --check-central
./mvnw clean verify
git tag -a v0.1.1 -m "DocRAG 0.1.1"
git push origin v0.1.1
```

Replace `0.1.1` with the new parent version for subsequent releases. Pushing the tag starts
`.github/workflows/release.yml`. Preflight requires a stable `vMAJOR.MINOR.PATCH` tag matching every module, a commit
reachable from `origin/main`, and six coordinates that are absent from Central. Network errors fail preflight; they
are not interpreted as missing coordinates. Then the same tagged commit passes reusable CI: native PostgreSQL/pgvector,
Postman provider simulations, Java tests, browser checks and the dependency security scan. These checks use no release
secrets. Publish jobs are serialized and an active release is never cancelled by another tag.

After CI passes, the publish job builds a signed local bundle with upload disabled and checks the exact artifact set,
POM metadata, sources/Javadoc contents, runtime license resources, signatures and checksums. It then reruns deploy on
the same checkout using `release-central`, which uploads, automatically publishes and waits for `PUBLISHED`. Tests are
skipped only in these packaging steps because the required CI job has already passed on this commit.

The final check waits for Central availability and compiles minimal core and Spring Boot consumers with empty Maven
settings and a fresh local repository. The Spring Boot consumer resolves all five libraries and their parent from
Central. Check the Actions run and Central Portal deployment status; signed bundles and available reports are retained
as run artifacts. Publication is confirmed only after these checks succeed; until then, README coordinates remain
marked as prepared.

For a local signed bundle check, using your GnuPG keyring:

```sh
./mvnw -Prelease-bundle clean deploy
python scripts/release.py --bundle target/central-publishing/central-bundle.zip --fingerprint <fingerprint>
```

On Windows, if GnuPG is not on PATH, pass `"-Dgpg.executable=<path-to-gpg.exe>"` to Maven and
`--gpg "<path-to-gpg.exe>"` to the bundle checker. For a CI-style BC signer dry
run, set the `MAVEN_GPG_*` environment variables securely and use
`./mvnw -Prelease-central -Ddocrag.central.skipUpload=true clean deploy`. Do not activate both release profiles
together.

If upload/validation fails, inspect the Actions logs and the deployment in Central Portal before retrying. If the
coordinates remain unpublished, correct the source and cut a new version/tag rather than moving an existing public
tag. Once Central reports `PUBLISHED`, never rerun the publishing job for that version. If only consumer verification
fails because of propagation, rerun `python scripts/release.py --verify-published` separately without republishing.
This check also runs without publishing credentials. Released artifacts cannot be overwritten; publish fixes as a new
version. Keep the README installation version aligned with the latest confirmed release.

### Verification status

Release verification is rerun against the repaired reactor, not inferred from the previously tested copy.
Native PostgreSQL/pgvector, real-model quality and representative overlapping CPU/memory workloads are separate gates.
The fifty generated questions remain unreviewed synthetic specifications. No public tag, publication, deployment or
live database change is performed by this preparation. Automated and simulated results are recorded here after checks.

Observed on 20 October 2026:

- The repaired reactor passed 59 Java tests with no failures/errors/skips in a fresh source tree and an isolated Maven
  cache containing third-party dependencies but no old or new SDK artifacts. The application passed three real
  affected-bean/template/security tests and packaging; its JAR contains the five new SDK artifacts.
- Postman CLI 1.75.0 recorded actual Docling hybrid-chunk and Ollama chat/embed HTTP requests through the complete
  client pipeline. Synthetic cases covered supported answers, misleading citations, wrong values, malformed/slow
  verification and HTTP 503 failure. These checks do not measure real model or OCR quality.
- One Chromium scenario passed for widget mounting, upload/progress, safe rendering, continuity, cancellation,
  keyboard focus and mobile layout. Both launch scripts served an authenticated example page on loopback.
- README plain-Java and financial-tool snippets compiled. Wrapper entry points and script syntax passed; core has no
  Spring dependency. Both Mermaid diagrams rendered and were visually inspected. Local documentation paths/anchors,
  workflow YAML, runtime license resources, sources/API artifacts and example packaging passed checks. Emptying the
  schema SQL in the isolated copy caused the build to fail at validation as intended.

SQL execution used isolated PGlite PostgreSQL WASM with pgvector and JDBC simple-query mode, not native PostgreSQL.
Native concurrency/prepared-protocol/query-plan verification remains blocked locally by unavailable Docker and missing
pgvector in the installed native PostgreSQL; native checks are configured in CI but not observed here. Real models,
reviewed-corpus accuracy, overlapping workloads, monitoring services, full host startup with live settings and an
executed vulnerability scan remain unverified. The signed profile's effective configuration was checked without
signing, uploading or publishing; namespace ownership, operator signatures, Central acceptance, private reporting and
the public release tag still require explicit maintainer verification.

Publishing automation was additionally checked on 20 October 2026: 19 Python release-guard tests passed; the complete
Maven reactor passed `clean verify` with no failures (native database/provider integration tests were not configured
locally); actionlint accepted both workflows; and the CI-style BC signer built a local bundle using a temporary RSA
key. All 21 artifact files passed signature, checksum, POM/JAR-content and module-exclusion checks. Two source exports
were byte-identical. Core and Spring Boot smoke consumers compiled using the locally installed build artifacts.
The temporary signing key was removed. No real publishing credentials, live Central upload or GitHub Actions release
run were exercised by these local checks; Central-only consumer resolution is performed after publication.

### Published release 0.1.1

Release `0.1.1` is published to Maven Central under `io.github.mgoyal05`. The
[successful release run](https://github.com/mgoyal05/doc-rag/actions/runs/39473985204) built tag `v0.1.1` at commit
`61064d70b57f495f9e3576094d8494a095f242ca`. The parent POM and all five libraries are available from the
[Maven Central namespace](https://central.sonatype.com/namespace/io.github.mgoyal05); the example remains excluded.

The tagged release passed 19 Python checks, 59 Java tests with no failures or skips, the browser scenario and the
dependency security scan. Its signed bundle passed all 21 artifact-file checks using the maintainer's signing key.
Central accepted and published the deployment, and core and Spring Boot smoke consumers compiled using empty Maven
settings and a fresh repository populated only from Central. All six published POM paths were independently checked
afterward. The pre-existing `0.1.0` and `v0.1.0` Git tags were preserved.
