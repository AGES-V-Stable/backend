-- Aceite do convite de membro (backend#74): quem aceitou, o vínculo criado e a versão dos termos aceita,
-- com o instante do aceite. Permite responder de forma idempotente a quem repete o mesmo aceite e
-- registra o consentimento. Só acrescenta colunas anuláveis: convites existentes (pendentes, revogados ou
-- aceitos antes desta versão) continuam válidos com as colunas nulas, e uma versão anterior da aplicação
-- segue funcionando com o schema novo. As chaves estrangeiras viram nulas se o usuário ou o vínculo forem
-- apagados, preservando o histórico do convite; os índices parciais evitam varrer a tabela nessas exclusões.
ALTER TABLE public.company_invitations
    ADD COLUMN accepted_by uuid,
    ADD COLUMN membership_id uuid,
    ADD COLUMN terms_version varchar(50),
    ADD COLUMN terms_accepted_at timestamp with time zone,
    ADD CONSTRAINT company_invitations_accepted_by_fkey FOREIGN KEY (accepted_by)
        REFERENCES public.users (id) ON DELETE SET NULL,
    ADD CONSTRAINT company_invitations_membership_fkey FOREIGN KEY (membership_id)
        REFERENCES public.company_memberships (id) ON DELETE SET NULL;

CREATE INDEX idx_company_invitations_accepted_by
    ON public.company_invitations(accepted_by) WHERE accepted_by IS NOT NULL;
CREATE INDEX idx_company_invitations_membership
    ON public.company_invitations(membership_id) WHERE membership_id IS NOT NULL;

-- Um e-mail identifica uma única conta, sem diferenciar maiúsculas: o aceite grava o e-mail do convite em
-- minúsculas e o cadastro inicial grava como foi digitado, e users_email_key (sensível a maiúsculas) não
-- impedia "maria@x.com" e "Maria@x.com" criados ao mesmo tempo. Falha se já houver contas duplicadas assim,
-- que precisam ser resolvidas antes desta versão.
CREATE UNIQUE INDEX users_email_lower_key ON public.users (lower(email));
