-- Hash dos links de convite substituídos por regeneração. Guarda só o hash (nunca o token nem uma cópia
-- cifrada): basta para reconhecer um link antigo e responder "substituído" em vez de "inexistente".
-- replaced_at é também o instante em que o link seguinte foi gerado, o que permite contar as gerações
-- de um convite na janela do limite de regeneração.
CREATE TABLE public.company_invitation_replaced_tokens
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    invitation_id uuid NOT NULL,
    token_hash varchar(64) NOT NULL,
    replaced_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT company_invitation_replaced_tokens_pkey PRIMARY KEY (id),
    CONSTRAINT company_invitation_replaced_tokens_token_hash_key UNIQUE (token_hash),
    CONSTRAINT company_invitation_replaced_tokens_invitation_fkey FOREIGN KEY (invitation_id)
        REFERENCES public.company_invitations (id) ON DELETE CASCADE
);

CREATE INDEX idx_company_invitation_replaced_tokens_invitation
    ON public.company_invitation_replaced_tokens(invitation_id, replaced_at);
