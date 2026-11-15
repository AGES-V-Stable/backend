-- Aceite dos termos de uso e da política de privacidade da V-Stable no cadastro próprio
-- (o fluxo de convite já registra o aceite em company_invitations). Contas antigas
-- ficam com NULL: não há evidência de aceite anterior a esta versão.
ALTER TABLE users
  ADD COLUMN terms_version VARCHAR(50),
  ADD COLUMN terms_accepted_at TIMESTAMPTZ;

-- KYC individual BlindPay recusado pode ser refeito com um novo cliente, como a BlindPay
-- exige. O cliente recusado anterior é guardado para que webhooks atrasados dele não
-- sejam associados ao novo cadastro.
ALTER TABLE blindpay_individual_kyc
  ADD COLUMN superseded_customer_id VARCHAR(50);
