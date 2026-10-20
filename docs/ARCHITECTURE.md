# Architecture and module map

DocRAG is a Maven reactor with a portable core, optional adapters and one runnable example. The root `pom.xml` owns
shared build rules and dependency versions; it is not an application. All Java modules target Java 21.

## Dependency direction

```text
examples/spring-boot/ (runnable host)
  └─ spring-boot/ (optional HTTP and SSE adapter)
       ├─ core/ (portable API, orchestration, storage and model boundary)
       ├─ docling/ (document extraction adapter) ──→ core/
       ├─ widget/ (browser assets)
       └─ observability/ (telemetry adapter) ──→ core/
```

`core/` has no Spring dependency. A plain Java consumer can use `core/` and optionally `docling/`; a web host adds
`spring-boot/`. `widget/` is packaged as browser resources, not as a Node.js runtime dependency. The example provides
authentication and application configuration; these remain the host's responsibility in integrations.

## Where things live

| Path                                                              | Purpose                                                                          | Start here                                                           |
|-------------------------------------------------------------------|----------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `core/src/main/java/docrag/`                                      | Public client, jobs, conversation flow, retrieval, validation, provider boundary | `ResearchClient.java`, `ResearchChatbot.java`, `ResearchEngine.java` |
| `core/src/main/resources/db/research/`                            | PostgreSQL schema for documents, vectors and conversations                       | `V1__research.sql`                                                   |
| `docling/src/main/java/docrag/docling/`                           | Docling Serve extraction and chunking                                            | `DoclingExtractor.java`                                              |
| `spring-boot/src/main/java/docrag/spring/`                        | Host-facing settings and HTTP/SSE controller                                     | `ResearchProperties.java`, `ResearchWebController.java`              |
| `widget/src/main/resources/META-INF/resources/document-research/` | Embeddable chat Web Component                                                    | `chat-widget.js`                                                     |
| `observability/src/main/java/docrag/telemetry/`                   | Optional research metrics and traces                                             | `ResearchTelemetry.java`                                             |
| `examples/spring-boot/src/main/`                                  | Standalone authenticated demonstration host                                      | `java/example/DocRagApplication.java`                                |
| `postman/`                                                        | Local simulated Ollama and Docling dependencies for tests                        | `simulations/research.sim.yaml`                                      |
| `scripts/`                                                        | Build, launch and source-release checks                                          | `build.sh`, `build.ps1`                                              |
| `.github/workflows/`                                              | CI build, database/browser checks and vulnerability scan                         | `ci.yml`                                                             |

Each module follows Maven's `src/main` and `src/test` layout. The root `compose.yaml` starts local PostgreSQL/pgvector
and Docling; `observability/deploy/` contains an optional monitoring stack. Generated `target/`, `node_modules/`,
`dist/`, `research-data/` and `research-demo/` are not source directories.

## Runtime ownership

1. The host authenticates the user, supplies a JDBC `DataSource`, model/extractor configuration and any trusted
   financial-data adapter. The Spring adapter converts HTTP requests to `ResearchClient` calls.
2. `ResearchClient` persists and schedules bounded jobs. `ResearchChatbot` coordinates upload, clarification and
   question turns. `ResearchEngine` indexes chunks and retrieves grounded evidence.
3. `PostgresResearchStore` stores versions, vectors and private conversation state. Original PDFs and job snapshots use
   the configured local archive directory. `DoclingExtractor` calls Docling Serve; `OllamaGateway` calls Ollama.
4. `AnswerValidator` checks the result before it is persisted. The controller sends progress over SSE; the widget then
   loads persisted history and citations.

The [method flow](MESSAGE-FLOW.md) traces these calls, and
the [README architecture diagram](../README.md#complete-architecture)
shows the external services and host boundary. [Operations](OPERATIONS.md) covers setup, evaluation and limitations.

## CI and evidence

`.github/workflows/ci.yml` is a verification workflow, not a deployment pipeline. It validates source files, runs the
Maven reactor against an isolated PostgreSQL/pgvector service and simulated providers, tests the widget in Chrome, then
scans dependencies with Grype. A high or critical advisory fails the job; the scan report and JUnit XML are uploaded
even on failure. This gate establishes dependency and automated-test status, not real-model answer quality. The
[evaluation guidance](OPERATIONS.md#verification-evaluation-and-adoption-limits) explains the separate model and
workload checks.
