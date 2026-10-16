ALTER TABLE public.beneficiaries
    ADD COLUMN avenia_sub_account_id varchar(255);

COMMENT ON COLUMN public.beneficiaries.avenia_sub_account_id IS
    'Avenia sub-account in which the external beneficiary identifier was registered';
