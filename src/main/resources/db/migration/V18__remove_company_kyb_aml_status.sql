-- Onboarding uses the representative's individual KYC only.
-- IF EXISTS also supports local databases that already applied the superseded development migrations.
-- Provider-specific compliance remains unchanged on provider_accounts.
ALTER TABLE companies
    DROP COLUMN IF EXISTS kyb_status,
    DROP COLUMN IF EXISTS aml_status;
