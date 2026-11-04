-- Token symbols such as USDC and BRLA exceed the three-letter fiat currency length.
ALTER TABLE beneficiaries ALTER COLUMN currency TYPE varchar(10);
ALTER TABLE base_transactions ALTER COLUMN foreign_currency TYPE varchar(10);
