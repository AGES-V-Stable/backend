ALTER TABLE public.administrators
        ADD COLUMN two_factor_secret VARCHAR(255),
        ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE public.users 
ALTER COLUMN two_factor_secret TYPE character varying(255);