-- Compatibilidade com bancos criados por uma versão anterior de V1. As entities
-- atuais já mapeiam estes campos; IF NOT EXISTS mantém a migration segura em bancos
-- novos, nos quais eles já fazem parte da criação inicial.
ALTER TABLE public.avenia_kyc_verifications
    ADD COLUMN IF NOT EXISTS document_id varchar(255),
    ADD COLUMN IF NOT EXISTS liveness_id varchar(255),
    ADD COLUMN IF NOT EXISTS avenia_sub_account_id varchar(255);

CREATE TABLE public.provider_accounts
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    provider varchar(50) NOT NULL,
    external_customer_id varchar(255),
    status varchar(50) NOT NULL DEFAULT 'PENDING',
    last_error_code varchar(100),
    synchronized_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT provider_accounts_pkey PRIMARY KEY (id),
    CONSTRAINT provider_accounts_company_provider_key UNIQUE (company_id, provider),
    CONSTRAINT provider_accounts_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies (id) ON DELETE CASCADE
);

CREATE TABLE public.beneficiary_provider_accounts
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    beneficiary_id uuid NOT NULL,
    provider varchar(50) NOT NULL,
    external_bank_account_id varchar(255),
    external_wallet_id varchar(255),
    status varchar(50) NOT NULL DEFAULT 'PENDING',
    last_error_code varchar(100),
    synchronized_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT beneficiary_provider_accounts_pkey PRIMARY KEY (id),
    CONSTRAINT beneficiary_provider_accounts_beneficiary_provider_key UNIQUE (beneficiary_id, provider),
    CONSTRAINT beneficiary_provider_accounts_beneficiary_fkey FOREIGN KEY (beneficiary_id)
        REFERENCES public.beneficiaries (id) ON DELETE CASCADE
);

CREATE TABLE public.provider_wallets
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    provider_account_id uuid NOT NULL,
    network varchar(50) NOT NULL,
    external_wallet_id varchar(255),
    status varchar(50) NOT NULL DEFAULT 'PENDING',
    last_error_code varchar(100),
    synchronized_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT provider_wallets_pkey PRIMARY KEY (id),
    CONSTRAINT provider_wallets_account_network_key UNIQUE (provider_account_id, network),
    CONSTRAINT provider_wallets_account_fkey FOREIGN KEY (provider_account_id)
        REFERENCES public.provider_accounts (id) ON DELETE CASCADE
);

ALTER TABLE public.beneficiaries
    ADD COLUMN payment_rail varchar(50) NOT NULL DEFAULT 'international_swift',
    ADD COLUMN account_class varchar(30),
    ADD COLUMN recipient_relationship varchar(50),
    ADD COLUMN iban varchar(50),
    ADD COLUMN routing_number varchar(20),
    ADD COLUMN address_line_1 varchar(255),
    ADD COLUMN address_line_2 varchar(255),
    ADD COLUMN city varchar(100),
    ADD COLUMN state_province_region varchar(100),
    ADD COLUMN postal_code varchar(20),
    ADD COLUMN country_code varchar(2),
    ADD COLUMN bank_address_line_1 varchar(255),
    ADD COLUMN bank_address_line_2 varchar(255),
    ADD COLUMN bank_city varchar(100),
    ADD COLUMN bank_state_province_region varchar(100),
    ADD COLUMN bank_postal_code varchar(20),
    ADD COLUMN bank_country_code varchar(2),
    ADD COLUMN swift_payment_code varchar(100);

CREATE TABLE public.quote_requests
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    beneficiary_id uuid NOT NULL,
    direction varchar(20) NOT NULL,
    source_currency varchar(10) NOT NULL,
    target_currency varchar(10) NOT NULL,
    source_payment_method varchar(50) NOT NULL,
    target_payment_method varchar(50) NOT NULL,
    requested_amount numeric(20, 8) NOT NULL,
    amount_side varchar(20) NOT NULL,
    token varchar(20),
    blockchain_network varchar(50),
    cover_fees boolean NOT NULL DEFAULT false,
    description varchar(128),
    status varchar(30) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT quote_requests_pkey PRIMARY KEY (id),
    CONSTRAINT quote_requests_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies (id) ON DELETE RESTRICT,
    CONSTRAINT quote_requests_user_fkey FOREIGN KEY (requested_by)
        REFERENCES public.users (id) ON DELETE RESTRICT,
    CONSTRAINT quote_requests_beneficiary_fkey FOREIGN KEY (beneficiary_id)
        REFERENCES public.beneficiaries (id) ON DELETE RESTRICT,
    CONSTRAINT quote_requests_amount_positive CHECK (requested_amount > 0)
);

CREATE TABLE public.provider_quotes
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    quote_request_id uuid NOT NULL,
    provider varchar(50) NOT NULL,
    external_quote_id varchar(255),
    idempotency_key varchar(255) NOT NULL,
    status varchar(30) NOT NULL,
    source_amount numeric(20, 8),
    target_amount numeric(20, 8),
    exchange_rate numeric(20, 10),
    provider_fee numeric(20, 8),
    service_fee numeric(20, 8),
    total_fee numeric(20, 8),
    expires_at timestamp with time zone,
    latency_ms bigint,
    failure_code varchar(100),
    failure_message varchar(500),
    provider_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT provider_quotes_pkey PRIMARY KEY (id),
    CONSTRAINT provider_quotes_request_provider_key UNIQUE (quote_request_id, provider),
    CONSTRAINT provider_quotes_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT provider_quotes_request_fkey FOREIGN KEY (quote_request_id)
        REFERENCES public.quote_requests (id) ON DELETE CASCADE
);

CREATE INDEX idx_provider_accounts_company ON public.provider_accounts(company_id);
CREATE INDEX idx_provider_wallets_account ON public.provider_wallets(provider_account_id);
CREATE INDEX idx_beneficiary_provider_accounts_beneficiary
    ON public.beneficiary_provider_accounts(beneficiary_id);
CREATE INDEX idx_quote_requests_company_created ON public.quote_requests(company_id, created_at DESC);
CREATE INDEX idx_provider_quotes_request ON public.provider_quotes(quote_request_id);
