UPDATE empresas
SET status_kyb = 'PENDENTE'
WHERE status_kyb IS NULL;

UPDATE empresas
SET status_aml = 'PENDENTE'
WHERE status_aml IS NULL;

UPDATE documentos_compliance
SET status = 'EM_ANALISE'
WHERE status IS NULL;

ALTER TABLE empresas
    ALTER COLUMN status_kyb SET NOT NULL,
    ALTER COLUMN status_aml SET NOT NULL;

ALTER TABLE documentos_compliance
    ALTER COLUMN status SET NOT NULL;
