# Security reporting and boundaries

Do not post credentials, private source documents or conversation text in a public issue. The intended channel is
[GitHub private vulnerability reporting](https://github.com/mgoyal05/doc-rag/security/advisories/new).
Maintainers must enable and test it before public release; this document does not establish that it is enabled.
If unavailable, do not post exploit details publicly; request a private channel without sensitive information.

Hosts authenticate callers and restrict administrative corpus operations. Uploaded documents are shared with all
authenticated users; conversations remain owner-scoped. Treat PDF content, OCR text, model outputs and tool responses
as untrusted evidence. Citation validation is not factual verification or prompt-injection immunity. Register only
trusted read-only tools, validate their scope, and avoid arbitrary SQL/URL execution. Restrict model/Docling services to
trusted networks; the sample local ports bind only to loopback. Browser configuration must contain no service
credentials.

Run automated checks against isolated services. The feature is disabled by default. Assess dependency advisories,
document parser isolation, authentication integration and real adversarial-model behavior before public deployment.
