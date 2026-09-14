CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'status_compliance_enum') THEN
        CREATE TYPE status_compliance_enum AS ENUM ('PENDENTE', 'EM_ANALISE', 'APROVADO', 'REJEITADO');
    END IF;
END$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'status_onboarding_enum') THEN
        CREATE TYPE status_onboarding_enum AS ENUM ('RASCUNHO', 'AGUARDANDO_COMPLIANCE', 'CONCLUIDO', 'ABANDONADO');
    END IF;
END$$;

CREATE TABLE IF NOT EXISTS empresas (
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    razao_social VARCHAR(255) NOT NULL,
    nome_fantasia VARCHAR(255),
    cnpj VARCHAR(18) NOT NULL,
    status_kyb status_compliance_enum DEFAULT 'PENDENTE',
    status_aml status_compliance_enum DEFAULT 'PENDENTE',
    saldo_disponivel_brl numeric(15, 2) DEFAULT 0.00,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT empresas_pkey PRIMARY KEY (id),
    CONSTRAINT empresas_cnpj_key UNIQUE (cnpj)
);

CREATE TABLE IF NOT EXISTS usuarios (
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    empresa_id uuid,
    nome_completo VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    hash_senha VARCHAR(255) NOT NULL,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT usuarios_pkey PRIMARY KEY (id),
    CONSTRAINT usuarios_email_key UNIQUE (email),
    CONSTRAINT usuarios_empresa_id_fkey FOREIGN KEY (empresa_id)
        REFERENCES empresas (id)
        ON UPDATE NO ACTION
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS progresso_cadastros (
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    usuario_id uuid NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(255) NOT NULL,
    etapa_atual INTEGER DEFAULT 1,
    status_geral status_onboarding_enum DEFAULT 'RASCUNHO',
    status_compliance_final status_compliance_enum DEFAULT 'PENDENTE',
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT progresso_cadastros_pkey PRIMARY KEY (id),
    CONSTRAINT progresso_cadastros_usuario_id_fkey FOREIGN KEY (usuario_id)
        REFERENCES usuarios (id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,
    CONSTRAINT progresso_cadastros_idempotency_key_key UNIQUE (idempotency_key)
);
