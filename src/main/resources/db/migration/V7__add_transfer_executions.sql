-- Endereço da carteira gerenciada pela BlindPay: é a origem (sender_wallet_address)
-- obrigatória dos payouts EVM.
ALTER TABLE public.provider_wallets
    ADD COLUMN IF NOT EXISTS address varchar(255);

-- Execução de uma oferta confirmada pelo usuário. A unicidade de provider_quote_id
-- garante que a mesma oferta nunca origine duas transferências; os IDs externos
-- únicos impedem que o mesmo payin/payout seja associado a duas execuções.
CREATE TABLE public.transfer_executions
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    provider_quote_id uuid NOT NULL,
    company_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    beneficiary_id uuid NOT NULL,
    transaction_id uuid,
    provider varchar(50) NOT NULL,
    status varchar(40) NOT NULL,
    source_currency varchar(10) NOT NULL,
    target_currency varchar(10) NOT NULL,
    source_amount numeric(20, 8) NOT NULL,
    target_amount numeric(20, 8) NOT NULL,
    total_fee numeric(20, 8),
    funded_amount numeric(20, 8),
    external_payin_quote_id varchar(255),
    external_payin_id varchar(255),
    funding_instructions text,
    external_payout_quote_id varchar(255),
    payout_quote_sender_amount numeric(20, 8),
    payout_quote_receiver_amount numeric(20, 8),
    payout_quote_expires_at timestamp with time zone,
    payout_quote_attempts integer NOT NULL DEFAULT 0,
    external_payout_id varchar(255),
    external_ticket_id varchar(255),
    failure_code varchar(100),
    failure_message varchar(500),
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp with time zone,
    CONSTRAINT transfer_executions_pkey PRIMARY KEY (id),
    CONSTRAINT transfer_executions_provider_quote_key UNIQUE (provider_quote_id),
    CONSTRAINT transfer_executions_amounts_positive CHECK (source_amount > 0 AND target_amount > 0),
    CONSTRAINT transfer_executions_provider_quote_fkey FOREIGN KEY (provider_quote_id)
        REFERENCES public.provider_quotes (id) ON DELETE RESTRICT,
    CONSTRAINT transfer_executions_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies (id) ON DELETE RESTRICT,
    CONSTRAINT transfer_executions_user_fkey FOREIGN KEY (requested_by)
        REFERENCES public.users (id) ON DELETE RESTRICT,
    CONSTRAINT transfer_executions_beneficiary_fkey FOREIGN KEY (beneficiary_id)
        REFERENCES public.beneficiaries (id) ON DELETE RESTRICT,
    CONSTRAINT transfer_executions_transaction_fkey FOREIGN KEY (transaction_id)
        REFERENCES public.base_transactions (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX transfer_executions_payin_key
    ON public.transfer_executions(external_payin_id) WHERE external_payin_id IS NOT NULL;
CREATE UNIQUE INDEX transfer_executions_payout_key
    ON public.transfer_executions(external_payout_id) WHERE external_payout_id IS NOT NULL;
CREATE INDEX idx_transfer_executions_status_updated
    ON public.transfer_executions(status, updated_at);
CREATE INDEX idx_transfer_executions_company ON public.transfer_executions(company_id);
