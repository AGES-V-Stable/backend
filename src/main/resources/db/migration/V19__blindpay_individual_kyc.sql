-- Isolates individual KYC from the company's existing BlindPay KYB account.
-- No CPF, date of birth, document content or selfie are persisted.
CREATE TABLE blindpay_individual_kyc (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  customer_id VARCHAR(50) UNIQUE,
  tos_id VARCHAR(50),
  tos_session_key UUID UNIQUE,
  tos_accepted_at TIMESTAMPTZ,
  kyc_status VARCHAR(50),
  aml_status VARCHAR(30),
  provider_updated_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
