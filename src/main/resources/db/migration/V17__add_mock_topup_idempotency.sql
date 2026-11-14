-- Uma mesma tentativa de crédito fictício altera o saldo apenas uma vez por empresa.
-- A chave é fornecida pelo cliente; nenhum dado de pagamento real é persistido.
-- A escrita participa da mesma transação que o incremento em companies.
CREATE TABLE public.mock_topup_receipts (
    id uuid NOT NULL PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES public.companies(id) ON DELETE CASCADE,
    request_key varchar(128) NOT NULL,
    amount_brl numeric(15, 2) NOT NULL CHECK (amount_brl > 0),
    resulting_balance_brl numeric(15, 2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mock_topup_receipts_unique_company_request UNIQUE (company_id, request_key)
);
