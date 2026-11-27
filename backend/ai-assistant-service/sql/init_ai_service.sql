CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS ai_service;

CREATE TABLE IF NOT EXISTS ai_service.knowledge_documents (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    lang VARCHAR(8) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    title TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_type, source_id, lang)
);

CREATE TABLE IF NOT EXISTS ai_service.knowledge_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES ai_service.knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    lang VARCHAR(8) NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(768),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_chunks_lang_source
    ON ai_service.knowledge_chunks (lang, source_type);

CREATE INDEX IF NOT EXISTS idx_ai_chunks_document_id
    ON ai_service.knowledge_chunks (document_id);
