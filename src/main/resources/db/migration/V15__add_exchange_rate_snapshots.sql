-- Última cotação de referência de cada par (cicd#55), compartilhada por todos os usuários.
-- Uma linha por par, atualizada por upsert quando a leitura encontra o valor vencido.
-- Não confundir com quote_requests/provider_quotes (V3), que são cotações de operações.
CREATE TABLE public.exchange_rate_snapshots
(
    id uuid NOT NULL,
    base_currency varchar(10) NOT NULL,
    quote_currency varchar(10) NOT NULL,
    rate numeric(24, 10) NOT NULL,
    provider varchar(50) NOT NULL,
    fetched_at timestamp with time zone NOT NULL,
    CONSTRAINT exchange_rate_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT exchange_rate_snapshots_pair_key UNIQUE (base_currency, quote_currency),
    CONSTRAINT exchange_rate_snapshots_rate_positive CHECK (rate > 0)
);
