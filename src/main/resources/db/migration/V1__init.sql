CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==============================================================================
-- 2. CRIACAO DOS TIPOS (ENUMS) - v8
-- ==============================================================================
CREATE TYPE admin_access_level_enum AS ENUM ('SUPER_ADMIN', 'ANALISTA_COMPLIANCE', 'SUPORTE');
CREATE TYPE receiving_method_enum AS ENUM ('CONTA_BANCARIA', 'CHAVE_PIX', 'WALLET_CRYPTO');
CREATE TYPE bank_account_type_enum AS ENUM ('checking', 'payment', 'savings', 'salary');
CREATE TYPE blockchain_network_enum AS ENUM ('ethereum', 'polygon', 'celo', 'gnosis', 'moonbeam', 'tron');
CREATE TYPE document_type_enum AS ENUM ('CONTRATO_SOCIAL', 'COMPROVANTE_ENDERECO', 'DOCUMENTO_REPRESENTANTE', 'OUTROS');
CREATE TYPE compliance_status_enum AS ENUM ('PENDENTE', 'EM_ANALISE', 'APROVADO', 'REJEITADO');
CREATE TYPE transaction_status_enum AS ENUM ('AGUARDANDO_PAGAMENTO', 'PROCESSANDO', 'RETIDO', 'LIQUIDADO', 'FALHA', 'FALHA_PARCIAL', 'CANCELADO', 'EXPIRADA');
CREATE TYPE transfer_method_enum AS ENUM ('TED', 'PIX', 'SALDO_EM_CONTA', 'BLOCKCHAIN');

-- ==============================================================================
-- 3. CRIACAO DAS TABELAS - v8
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.administrators
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    full_name character varying(255) COLLATE pg_catalog."default" NOT NULL,
    email character varying(255) COLLATE pg_catalog."default" NOT NULL,
    phone character varying(20) COLLATE pg_catalog."default",
    position character varying(100) COLLATE pg_catalog."default",
    password_hash character varying(255) COLLATE pg_catalog."default" NOT NULL,
    password_salt character varying(255) COLLATE pg_catalog."default" NOT NULL,
    access_level admin_access_level_enum DEFAULT 'SUPER_ADMIN'::admin_access_level_enum,
    require_2fa boolean DEFAULT true,
    force_password_change boolean DEFAULT true,
    new_access_alert boolean DEFAULT true,
    active boolean DEFAULT true,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT administrators_pkey PRIMARY KEY (id),
    CONSTRAINT administrators_email_key UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS public.companies
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    legal_name character varying(255) COLLATE pg_catalog."default" NOT NULL,
    trade_name character varying(255) COLLATE pg_catalog."default",
    cnpj character varying(18) COLLATE pg_catalog."default" NOT NULL,
    country character varying(100) COLLATE pg_catalog."default" DEFAULT 'Brasil'::character varying,
    zip_code character varying(20) COLLATE pg_catalog."default",
    city character varying(100) COLLATE pg_catalog."default",
    state character varying(50) COLLATE pg_catalog."default",
    main_activity character varying(255) COLLATE pg_catalog."default",
    account_purpose character varying(255) COLLATE pg_catalog."default",
    transactions_purpose character varying(255) COLLATE pg_catalog."default",
    estimated_annual_revenue character varying(100) COLLATE pg_catalog."default",
    source_of_funds character varying(255) COLLATE pg_catalog."default",
    kyb_status compliance_status_enum DEFAULT 'PENDENTE'::compliance_status_enum,
    aml_status compliance_status_enum DEFAULT 'PENDENTE'::compliance_status_enum,
    kyc_usd_approved boolean DEFAULT false,
    kyc_cop_approved boolean DEFAULT false,
    available_balance_brl numeric(15, 2) DEFAULT 0.00,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT companies_pkey PRIMARY KEY (id),
    CONSTRAINT companies_cnpj_key UNIQUE (cnpj)
);

CREATE TABLE IF NOT EXISTS public.users
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    full_name character varying(255) COLLATE pg_catalog."default" NOT NULL,
    cpf character varying(14) COLLATE pg_catalog."default",
    birth_date date,
    email character varying(255) COLLATE pg_catalog."default" NOT NULL,
    phone character varying(20) COLLATE pg_catalog."default",
    position character varying(100) COLLATE pg_catalog."default",
    country_of_residence character varying(100) COLLATE pg_catalog."default" DEFAULT 'Brasil'::character varying,
    password_hash character varying(255) COLLATE pg_catalog."default" NOT NULL,
    password_salt character varying(255) COLLATE pg_catalog."default" NOT NULL,
    two_factor_secret character varying(100) COLLATE pg_catalog."default",
    two_factor_enabled boolean DEFAULT false,
    active boolean DEFAULT true,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT users_cpf_key UNIQUE (cpf),
    CONSTRAINT users_email_key UNIQUE (email)
);

-- Nova Tabela: Verificacoes de Reconhecimento Facial (Avenia)
CREATE TABLE IF NOT EXISTS public.avenia_kyc_verifications
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    user_id uuid NOT NULL,
    avenia_process_id character varying(255) COLLATE pg_catalog."default",
    status compliance_status_enum DEFAULT 'PENDENTE'::compliance_status_enum,
    response_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT avenia_kyc_verifications_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.beneficiaries
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    avenia_id uuid,
    avenia_wallet_id uuid,
    nickname character varying(100) COLLATE pg_catalog."default" NOT NULL,
    internal_description character varying(255) COLLATE pg_catalog."default",
    receiving_method receiving_method_enum NOT NULL DEFAULT 'CONTA_BANCARIA'::receiving_method_enum,
    pix_key character varying(255) COLLATE pg_catalog."default",
    identification_document character varying(20) COLLATE pg_catalog."default",
    account_holder_name character varying(255) COLLATE pg_catalog."default",
    bank_code character varying(10) COLLATE pg_catalog."default",
    branch_number character varying(20) COLLATE pg_catalog."default",
    account_number character varying(50) COLLATE pg_catalog."default",
    account_type bank_account_type_enum,
    country character varying(100) COLLATE pg_catalog."default",
    blockchain_network blockchain_network_enum,
    wallet_address character varying(120) COLLATE pg_catalog."default",
    wallet_memo character varying(100) COLLATE pg_catalog."default",
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT beneficiaries_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.compliance_documents
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    document_type document_type_enum NOT NULL,
    file_name character varying(255) COLLATE pg_catalog."default" NOT NULL,
    file_url text COLLATE pg_catalog."default" NOT NULL,
    file_size_bytes bigint,
    status compliance_status_enum DEFAULT 'EM_ANALISE'::compliance_status_enum,
    uploaded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT compliance_documents_pkey PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS public.base_transactions
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    creator_user_id uuid,
    status transaction_status_enum DEFAULT 'PROCESSANDO'::transaction_status_enum,
    foreign_currency character varying(3) COLLATE pg_catalog."default" NOT NULL,
    foreign_amount numeric(15, 2) NOT NULL,
    settlement_amount_brl numeric(15, 2),
    service_fee_brl numeric(10, 2) DEFAULT 0.00,
    effective_spread_percentage numeric(5, 4),
    exchange_rate numeric(12, 6),
    avenia_ticket_id uuid,
    blockchain_transaction_hash character varying(120) COLLATE pg_catalog."default",
    settled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT base_transactions_pkey PRIMARY KEY (id),
    CONSTRAINT base_transactions_avenia_ticket_id_key UNIQUE (avenia_ticket_id)
);

CREATE TABLE IF NOT EXISTS public.export_transactions
(
    transaction_id uuid NOT NULL,
    external_billing_code character varying(255) COLLATE pg_catalog."default" NOT NULL,
    external_payer_name character varying(255) COLLATE pg_catalog."default" NOT NULL,
    external_payer_email character varying(255) COLLATE pg_catalog."default",
    due_date date NOT NULL,
    reason_description text COLLATE pg_catalog."default" NOT NULL,
    CONSTRAINT export_transactions_pkey PRIMARY KEY (transaction_id),
    CONSTRAINT export_transactions_external_billing_code_key UNIQUE (external_billing_code)
);

CREATE TABLE IF NOT EXISTS public.import_transactions
(
    transaction_id uuid NOT NULL,
    beneficiary_id uuid NOT NULL,
    transfer_method transfer_method_enum NOT NULL,
    CONSTRAINT import_transactions_pkey PRIMARY KEY (transaction_id)
);

-- ==============================================================================
-- 4. APLICACAO DAS CONSTRAINTS (FOREIGN KEYS)
-- ==============================================================================

ALTER TABLE IF EXISTS public.users
    ADD CONSTRAINT users_company_id_fkey FOREIGN KEY (company_id)
    REFERENCES public.companies (id) MATCH SIMPLE
    ON UPDATE NO ACTION
    ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_users_company
    ON public.users(company_id);

ALTER TABLE IF EXISTS public.avenia_kyc_verifications
    ADD CONSTRAINT avenia_kyc_verifications_user_id_fkey FOREIGN KEY (user_id)
    REFERENCES public.users (id) MATCH SIMPLE
    ON UPDATE CASCADE
    ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_kyc_user
    ON public.avenia_kyc_verifications(user_id);

ALTER TABLE IF EXISTS public.beneficiaries
    ADD CONSTRAINT beneficiaries_company_id_fkey FOREIGN KEY (company_id)
    REFERENCES public.companies (id) MATCH SIMPLE
    ON UPDATE NO ACTION
    ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_beneficiaries_company
    ON public.beneficiaries(company_id);

ALTER TABLE IF EXISTS public.compliance_documents
    ADD CONSTRAINT compliance_documents_company_id_fkey FOREIGN KEY (company_id)
    REFERENCES public.companies (id) MATCH SIMPLE
    ON UPDATE NO ACTION
    ON DELETE CASCADE;

ALTER TABLE IF EXISTS public.base_transactions
    ADD CONSTRAINT base_transactions_company_id_fkey FOREIGN KEY (company_id)
    REFERENCES public.companies (id) MATCH SIMPLE
    ON UPDATE NO ACTION
    ON DELETE RESTRICT;
CREATE INDEX IF NOT EXISTS idx_base_transactions_company
    ON public.base_transactions(company_id);

ALTER TABLE IF EXISTS public.base_transactions
    ADD CONSTRAINT base_transactions_creator_user_id_fkey FOREIGN KEY (creator_user_id)
    REFERENCES public.users (id) MATCH SIMPLE
    ON UPDATE NO ACTION
    ON DELETE SET NULL;

ALTER TABLE IF EXISTS public.export_transactions
    ADD CONSTRAINT export_transactions_transaction_id_fkey FOREIGN KEY (transaction_id)
    REFERENCES public.base_transactions (id) MATCH SIMPLE
    ON UPDATE CASCADE
    ON DELETE CASCADE;

ALTER TABLE IF EXISTS public.import_transactions
    ADD CONSTRAINT import_transactions_beneficiary_id_fkey FOREIGN KEY (beneficiary_id)
    REFERENCES public.beneficiaries (id) MATCH SIMPLE
    ON UPDATE NO ACTION
    ON DELETE RESTRICT;
CREATE INDEX IF NOT EXISTS idx_import_transactions_beneficiary
    ON public.import_transactions(beneficiary_id);

ALTER TABLE IF EXISTS public.import_transactions
    ADD CONSTRAINT import_transactions_transaction_id_fkey FOREIGN KEY (transaction_id)
    REFERENCES public.base_transactions (id) MATCH SIMPLE
    ON UPDATE CASCADE
    ON DELETE CASCADE;
