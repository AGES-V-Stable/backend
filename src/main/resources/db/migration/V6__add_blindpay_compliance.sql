-- Compliance (KYB/AML) por provedor. Os status ficam na conta do provedor e não
-- substituem companies.kyb_status/aml_status, que seguem o fluxo da Avenia.
--
-- Esta migration nasceu como V4 na branch do BlindPay e colidia com
-- V4__expand_beneficiary_country_codes da main. Foi renumerada para depois de V5
-- e escrita de forma idempotente: bancos de desenvolvimento que já aplicaram a
-- versão antiga não falham ao reaplicá-la (ver docs/blindpay-transferencias-sandbox.md).
ALTER TABLE public.provider_accounts
    ADD COLUMN IF NOT EXISTS kyc_status varchar(50),
    ADD COLUMN IF NOT EXISTS aml_status varchar(20),
    ADD COLUMN IF NOT EXISTS tos_id varchar(15),
    ADD COLUMN IF NOT EXISTS tos_accepted_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS provider_updated_at timestamp with time zone;

CREATE UNIQUE INDEX IF NOT EXISTS provider_accounts_provider_external_customer_key
    ON public.provider_accounts(provider, external_customer_id)
    WHERE external_customer_id IS NOT NULL;

-- Correlação entre a sessão de aceite dos termos (fluxo hospedado da BlindPay)
-- e a empresa. A idempotency_key é devolvida no webhook tos.accept.
CREATE TABLE IF NOT EXISTS public.blindpay_tos_sessions
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    status varchar(20) NOT NULL,
    tos_id varchar(15),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at timestamp with time zone,
    CONSTRAINT blindpay_tos_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT blindpay_tos_sessions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT blindpay_tos_sessions_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies (id) ON DELETE CASCADE,
    CONSTRAINT blindpay_tos_sessions_user_fkey FOREIGN KEY (requested_by)
        REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_blindpay_tos_sessions_company ON public.blindpay_tos_sessions(company_id);

-- Mensagens de webhook já processadas, pelo svix-id (repetido nas retentativas).
CREATE TABLE IF NOT EXISTS public.blindpay_webhook_events
(
    message_id varchar(255) NOT NULL,
    event_type varchar(50),
    received_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT blindpay_webhook_events_pkey PRIMARY KEY (message_id)
);
