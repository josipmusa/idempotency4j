-- Apply before starting idempotency4j 0.2 applications that use an externally managed schema.
ALTER TABLE idempotency_records
    ADD COLUMN lease_id VARCHAR(36) NULL;
