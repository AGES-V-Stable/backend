ALTER TABLE beneficiaries
    ADD COLUMN is_self_custody boolean,
    ADD COLUMN custodian_name varchar(100),
    ADD COLUMN counterparty_name varchar(100),
    ADD COLUMN counterparty_country varchar(2),
    ADD COLUMN is_same_economic_group boolean;
