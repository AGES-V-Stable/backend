ALTER TABLE public.beneficiaries
    ADD COLUMN beneficiary_type varchar(50) NOT NULL DEFAULT 'LEGAL_ENTITY',
    ADD COLUMN bank_name varchar(255),
    ADD COLUMN swift_bic varchar(20),
    ADD COLUMN currency varchar(3),
    ADD COLUMN address varchar(255);
