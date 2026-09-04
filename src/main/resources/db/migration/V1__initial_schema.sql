-- ---------------------------------------------------------------------
-- EXTENSÕES
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- ENUMS PARA PADRONIZAÇÃO
-- ---------------------------------------------------------------------
CREATE TYPE status_compliance_enum AS ENUM ('PENDENTE', 'EM_ANALISE', 'APROVADO', 'REJEITADO');
CREATE TYPE metodo_transferencia_enum AS ENUM ('TED', 'PIX');
CREATE TYPE status_transferencia_enum AS ENUM ('PROCESSANDO', 'LIQUIDADO', 'FALHA', 'CANCELADO');
CREATE TYPE tipo_documento_enum AS ENUM ('CONTRATO_SOCIAL', 'COMPROVANTE_ENDERECO', 'DOCUMENTO_REPRESENTANTE', 'OUTROS');
CREATE TYPE tipo_conta_bancaria_enum AS ENUM ('checking', 'payment', 'savings', 'salary');
CREATE TYPE nivel_acesso_admin_enum AS ENUM ('SUPER_ADMIN', 'ANALISTA_COMPLIANCE', 'SUPORTE');
CREATE TYPE direcao_operacao_enum AS ENUM ('IMPORTACAO', 'EXPORTACAO');
-- IMPORTACAO: Empresa envia dinheiro para fora (Paga fornecedor)
-- EXPORTACAO: Empresa recebe dinheiro de fora (Recebe de cliente estrangeiro)
CREATE TYPE status_fatura_enum AS ENUM ('AGUARDANDO_PAGAMENTO', 'PAGA', 'CANCELADA', 'EXPIRADA');
CREATE TYPE status_onboarding_enum AS ENUM ('RASCUNHO', 'AGUARDANDO_COMPLIANCE', 'CONCLUIDO', 'ABANDONADO');
-- ---------------------------------------------------------------------
-- 1. TABELA DE EMPRESAS (PME / COOPERATIVAS DO AGRO)
-- ---------------------------------------------------------------------
CREATE TABLE empresas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    razao_social VARCHAR(255) NOT NULL,
    nome_fantasia VARCHAR(255),                             
    cnpj VARCHAR(18) UNIQUE NOT NULL,
    status_kyb status_compliance_enum DEFAULT 'PENDENTE',
    status_aml status_compliance_enum DEFAULT 'PENDENTE',
    saldo_disponivel_brl NUMERIC(15, 2) DEFAULT 0.00,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
-- ---------------------------------------------------------------------
-- 2. TABELA DE USUÁRIOS
-- ---------------------------------------------------------------------
CREATE TABLE usuarios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    nome_completo VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    hash_senha VARCHAR(255) NOT NULL,
    segredo_2fa VARCHAR(100),
    habilitado_2fa BOOLEAN DEFAULT FALSE,
    ativo BOOLEAN DEFAULT TRUE,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 3. DOCUMENTOS DE COMPLIANCE
-- ---------------------------------------------------------------------
CREATE TABLE documentos_compliance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    tipo_documento tipo_documento_enum NOT NULL,
    nome_arquivo VARCHAR(255) NOT NULL,
    url_arquivo TEXT NOT NULL,
    tamanho_arquivo_bytes BIGINT,
    status status_compliance_enum DEFAULT 'EM_ANALISE',
    enviado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 4. BENEFICIÁRIOS GLOBAIS (Núcleo Bancário - API Avenia)
-- ---------------------------------------------------------------------
CREATE TABLE beneficiarios_globais (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    avenia_id UUID UNIQUE,                               -- ID retornado pela API da Avenia
    
    -- Dados PIX
    chave_pix VARCHAR(255) UNIQUE,
    
    -- Dados Bancários Tradicionais
    documento_identificacao VARCHAR(20),                 -- taxId (CPF/CNPJ)
    nome_titular VARCHAR(255),                           -- userName
    codigo_banco VARCHAR(10),                            -- bankCode
    agencia VARCHAR(20),                                 -- branchCode
    numero_conta VARCHAR(50),                            -- accountNumber
    tipo_conta tipo_conta_bancaria_enum,                 -- accountType
    
    -- Configuração de Diretório
    visivel_globalmente BOOLEAN DEFAULT FALSE,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_dados_bancarios UNIQUE NULLS NOT DISTINCT (documento_identificacao, codigo_banco, agencia, numero_conta),
    CONSTRAINT chk_pix_ou_banco CHECK (
        chave_pix IS NOT NULL OR 
        (documento_identificacao IS NOT NULL AND nome_titular IS NOT NULL AND codigo_banco IS NOT NULL AND agencia IS NOT NULL AND numero_conta IS NOT NULL AND tipo_conta IS NOT NULL)
    )
);

-- ---------------------------------------------------------------------
-- 5. BENEFICIÁRIOS LOCAIS (Agenda Privada da Empresa)
-- ---------------------------------------------------------------------
CREATE TABLE beneficiarios_locais (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    beneficiario_global_id UUID NOT NULL REFERENCES beneficiarios_globais(id) ON DELETE RESTRICT,
    
    -- Campos privados mapeados para a Avenia (alias e description)
    apelido VARCHAR(100) NOT NULL,
    descricao VARCHAR(255),
    
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_empresa_beneficiario UNIQUE (empresa_id, beneficiario_global_id)
);


-- ---------------------------------------------------------------------
-- 6. TABELA DE FATURAS (INVOICES DE EXPORTAÇÃO) - MOVIDA PARA CIMA
-- ---------------------------------------------------------------------
CREATE TABLE faturas_exportacao (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID NOT NULL REFERENCES empresas(id) ON DELETE CASCADE,
    codigo_cobranca_externa VARCHAR(255) UNIQUE NOT NULL, 
    nome_pagador_externo VARCHAR(255) NOT NULL,
    email_pagador_externo VARCHAR(255),
    moeda_solicitada VARCHAR(3) NOT NULL,              
    valor_solicitado NUMERIC(15, 2) NOT NULL,          
    descricao_servico TEXT NOT NULL,
    data_vencimento DATE NOT NULL,
    status status_fatura_enum DEFAULT 'AGUARDANDO_PAGAMENTO',
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 7. TABELA DE TRANSFERÊNCIAS / LIQUIDAÇÕES
-- ---------------------------------------------------------------------
CREATE TABLE transferencias (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID NOT NULL REFERENCES empresas(id) ON DELETE RESTRICT,
    usuario_criador_id UUID REFERENCES usuarios(id) ON DELETE SET NULL,

    direcao_operacao direcao_operacao_enum NOT NULL,

    beneficiario_local_id UUID REFERENCES beneficiarios_locais(id) ON DELETE RESTRICT,
    fatura_id UUID REFERENCES faturas_exportacao(id) ON DELETE RESTRICT,

    metodo_transferencia metodo_transferencia_enum NOT NULL, 
    valor_brl NUMERIC(15, 2) NOT NULL,                      
    taxa_servico_brl NUMERIC(10, 2) DEFAULT 0.00,

    moeda_estrangeira VARCHAR(3) NOT NULL,                  
    valor_estrangeiro NUMERIC(15, 2) NOT NULL,              
    percentual_spread_efetivo NUMERIC(5, 4) NOT NULL,
    taxa_cambio NUMERIC(12, 6) NOT NULL,

    status status_transferencia_enum DEFAULT 'PROCESSANDO',
    hash_transacao_blockchain VARCHAR(120),
    liquidado_em TIMESTAMPTZ,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_direcao_vinculo CHECK (
        (direcao_operacao = 'IMPORTACAO' AND beneficiario_local_id IS NOT NULL AND fatura_id IS NULL) OR
        (direcao_operacao = 'EXPORTACAO' AND fatura_id IS NOT NULL AND beneficiario_local_id IS NULL)
    )
);

-- ---------------------------------------------------------------------
-- 8. TABELA DE ADMINISTRADORES
-- ---------------------------------------------------------------------
CREATE TABLE administradores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nome_completo VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,                   
    hash_senha VARCHAR(255) NOT NULL,                     
    nivel_acesso nivel_acesso_admin_enum DEFAULT 'SUPER_ADMIN',
    ativo BOOLEAN DEFAULT TRUE,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 9. PROGRESSO DE CADASTRO (ONBOARDING)
-- ---------------------------------------------------------------------
CREATE TABLE progresso_cadastros (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id UUID REFERENCES empresas(id) ON DELETE CASCADE,
    email_contato VARCHAR(255) NOT NULL,
    etapa_atual INTEGER DEFAULT 1,
    status_geral status_onboarding_enum DEFAULT 'RASCUNHO',
    status_compliance_final status_compliance_enum DEFAULT 'PENDENTE',
    dados_temporarios JSONB DEFAULT '{}'::jsonb,
    criado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- ÍNDICES DE PERFORMANCE
-- ---------------------------------------------------------------------
CREATE INDEX idx_empresas_cnpj ON empresas(cnpj);
CREATE INDEX idx_usuarios_empresa ON usuarios(empresa_id);
CREATE INDEX idx_beneficiarios_globais_pix ON beneficiarios_globais(chave_pix);
CREATE INDEX idx_beneficiarios_globais_visivel ON beneficiarios_globais(visivel_globalmente);
CREATE INDEX idx_beneficiarios_locais_empresa ON beneficiarios_locais(empresa_id);
CREATE INDEX idx_transferencias_empresa ON transferencias(empresa_id);
CREATE INDEX idx_transferencias_beneficiario ON transferencias(beneficiario_local_id);
CREATE INDEX idx_transferencias_status_data ON transferencias(status, criado_em DESC);
CREATE INDEX idx_administradores_email ON administradores(email);
CREATE INDEX idx_faturas_codigo ON faturas_exportacao(codigo_cobranca_externa);
CREATE INDEX idx_faturas_empresa ON faturas_exportacao(empresa_id);
CREATE INDEX idx_progresso_cadastros_empresa ON progresso_cadastros(empresa_id);