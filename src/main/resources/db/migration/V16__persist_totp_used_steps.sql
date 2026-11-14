-- Um passo TOTP consumido por conta, compartilhado entre todas as instâncias Lambda.
-- Não armazena o código, o segredo nem o e-mail. Atualizações são monotônicas e
-- participam da transação de confirmação; rollback não consome o código.
CREATE TABLE public.totp_used_steps (
    principal_key char(64) NOT NULL PRIMARY KEY,
    last_time_step bigint NOT NULL
);
