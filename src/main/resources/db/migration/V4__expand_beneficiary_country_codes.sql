-- Avenia uses ISO alpha-3 country codes for USD and EUR beneficiary accounts,
-- while SWIFT uses alpha-2. The original columns only accepted alpha-2.
ALTER TABLE public.beneficiaries
    ALTER COLUMN country_code TYPE varchar(3),
    ALTER COLUMN bank_country_code TYPE varchar(3);
