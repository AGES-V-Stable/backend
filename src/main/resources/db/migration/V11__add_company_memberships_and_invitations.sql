-- Vínculo de um usuário com uma empresa e seu perfil de acesso (OWNER, OPERATOR, VIEWER).
-- A identidade de login continua em users; users.company_id é mantido. Cada usuário tem no
-- máximo um vínculo ACTIVE e cada empresa no máximo um OWNER ACTIVE; vínculos REMOVED ficam
-- como histórico.
CREATE TABLE public.company_memberships
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 1,
    joined_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    removed_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT company_memberships_pkey PRIMARY KEY (id),
    CONSTRAINT company_memberships_removed_at_consistent
        CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL)),
    CONSTRAINT company_memberships_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies (id) ON DELETE CASCADE,
    CONSTRAINT company_memberships_user_fkey FOREIGN KEY (user_id)
        REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX company_memberships_active_user_key
    ON public.company_memberships(user_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX company_memberships_active_owner_key
    ON public.company_memberships(company_id) WHERE role = 'OWNER' AND status = 'ACTIVE';
CREATE INDEX idx_company_memberships_user ON public.company_memberships(user_id);
CREATE INDEX idx_company_memberships_company_status
    ON public.company_memberships(company_id, status);

-- Convite de membro compartilhado manualmente por link, sem expiração por tempo. O hash valida o
-- token e permanece depois do aceite/revogação; a cópia cifrada que permite ao proprietário copiar
-- o link de novo só existe enquanto o convite está PENDING.
CREATE TABLE public.company_invitations
(
    id uuid NOT NULL DEFAULT uuid_generate_v4(),
    company_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    email varchar(255) NOT NULL,
    role varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    version bigint NOT NULL DEFAULT 1,
    token_hash varchar(64) NOT NULL,
    token_ciphertext text,
    token_key_version varchar(20),
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at timestamp with time zone,
    revoked_at timestamp with time zone,
    CONSTRAINT company_invitations_pkey PRIMARY KEY (id),
    CONSTRAINT company_invitations_token_hash_key UNIQUE (token_hash),
    CONSTRAINT company_invitations_role_not_owner CHECK (role <> 'OWNER'),
    CONSTRAINT company_invitations_recoverable_token_only_pending CHECK (
        (status = 'PENDING' AND token_ciphertext IS NOT NULL AND token_key_version IS NOT NULL)
        OR (status <> 'PENDING' AND token_ciphertext IS NULL AND token_key_version IS NULL)),
    CONSTRAINT company_invitations_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies (id) ON DELETE CASCADE,
    CONSTRAINT company_invitations_created_by_fkey FOREIGN KEY (created_by)
        REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX company_invitations_pending_email_key
    ON public.company_invitations(company_id, lower(email)) WHERE status = 'PENDING';
CREATE INDEX idx_company_invitations_company_status
    ON public.company_invitations(company_id, status);

-- Usuários existentes passam a ter vínculo. O proprietário só é identificado sem ambiguidade
-- quando a empresa tem um único usuário; com vários, ninguém é promovido automaticamente e todos
-- viram OPERATOR (mantêm o acesso operacional de hoje) até a validação do proprietário.
-- Empresas sem usuário não recebem vínculo.
INSERT INTO public.company_memberships (company_id, user_id, role, joined_at)
SELECT u.company_id,
       u.id,
       CASE WHEN COUNT(*) OVER (PARTITION BY u.company_id) = 1 THEN 'OWNER' ELSE 'OPERATOR' END,
       COALESCE(u.created_at, CURRENT_TIMESTAMP)
FROM public.users u;

DO $$
DECLARE
    companies_without_owner text;
BEGIN
    SELECT string_agg(company_id::text, ', ') INTO companies_without_owner
    FROM (SELECT company_id FROM public.users GROUP BY company_id HAVING COUNT(*) > 1) multi_user_companies;
    IF companies_without_owner IS NOT NULL THEN
        RAISE NOTICE 'Empresas com vários usuários ficaram sem proprietário e exigem validação manual: %',
            companies_without_owner;
    END IF;
END $$;
