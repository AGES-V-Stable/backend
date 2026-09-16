ALTER TABLE documentos_compliance
    ADD COLUMN mime_type VARCHAR(100),
    ADD COLUMN conteudo BYTEA,
    ADD COLUMN hash_sha256 VARCHAR(64);

CREATE INDEX idx_documentos_compliance_empresa
    ON documentos_compliance (empresa_id);
