-- Explicit setup only. The operator must install pgvector >= 0.8 first.
CREATE SCHEMA IF NOT EXISTS ${schema};
CREATE TABLE ${schema}.profile
(
    singleton       boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    schema_version  integer NOT NULL,
    embedding_model text    NOT NULL,
    dimensions      integer NOT NULL
);
CREATE TABLE ${schema}.documents
(
    id             text PRIMARY KEY,
    kind           text        NOT NULL CHECK (kind IN ('UPLOAD', 'TELEGRAM')),
    company        text        NOT NULL DEFAULT '',
    period_keys    text[] NOT NULL DEFAULT '{}',
    metadata_json  text        NOT NULL DEFAULT '{}',
    source         text        NOT NULL,
    file_path      text        NOT NULL,
    fingerprint    text        NOT NULL,
    active_version text,
    state          text        NOT NULL,
    draft_json     text,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX documents_scope ON ${schema}.documents (kind, company);
CREATE INDEX documents_fingerprint ON ${schema}.documents (kind, fingerprint);
CREATE TABLE ${schema}.chunks
(
    id                 text PRIMARY KEY,
    document_id        text    NOT NULL REFERENCES ${schema}.documents (id),
    version            text    NOT NULL,
    ordinal            integer NOT NULL,
    text               text    NOT NULL,
    embedding_text     text    NOT NULL,
    pages_json         text    NOT NULL,
    headings_json      text    NOT NULL,
    tokens             integer NOT NULL,
    extraction_version text    NOT NULL,
    embedding          vector(${dimensions}) NOT NULL,
    search_text        tsvector GENERATED ALWAYS AS (to_tsvector('english', embedding_text)) STORED,
    UNIQUE (document_id, version, ordinal)
);
CREATE INDEX chunks_text ON ${schema}.chunks USING gin(search_text);
CREATE INDEX chunks_vector ON ${schema}.chunks USING hnsw(embedding vector_cosine_ops);
CREATE TABLE ${schema}.conversations
(
    id               uuid PRIMARY KEY,
    owner            text        NOT NULL,
    title            text        NOT NULL DEFAULT 'New conversation',
    active_turn      uuid,
    scope_json       text,
    pending_document text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX conversation_owner ON ${schema}.conversations (owner, updated_at DESC);
CREATE TABLE ${schema}.turns
(
    id              uuid PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES ${schema}.conversations (id),
    kind            text        NOT NULL,
    text            text        NOT NULL,
    payload_json    text,
    state           text        NOT NULL,
    result_json     text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX turn_history ON ${schema}.turns (conversation_id, created_at DESC, id);
