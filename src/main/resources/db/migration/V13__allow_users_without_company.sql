-- Conta de acesso independente da empresa (backend#64): o usuário pode se cadastrar e autenticar
-- antes de cadastrar a empresa. Enquanto não houver vínculo, company_id fica nulo e o acesso a dados
-- empresariais continua negado, pois depende do vínculo ativo em company_memberships.
-- Usuários existentes não mudam: todos já têm empresa. A chave estrangeira continua valendo para
-- os valores preenchidos.
ALTER TABLE public.users ALTER COLUMN company_id DROP NOT NULL;
