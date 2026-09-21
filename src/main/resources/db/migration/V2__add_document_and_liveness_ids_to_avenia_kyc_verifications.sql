ALTER TABLE avenia_kyc_verifications
    ADD COLUMN document_id VARCHAR(255),
    ADD COLUMN liveness_id VARCHAR(255);
