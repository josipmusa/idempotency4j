CREATE TABLE IF NOT EXISTS idempotency_records (
    scope             VARCHAR(128)  NOT NULL,
    idempotency_key   VARCHAR(255)  NOT NULL,
    status            VARCHAR(12)   NOT NULL,
    lease_id          VARCHAR(36)   NULL,
    lease_expires_at  TIMESTAMP(6)  NULL,
    fingerprint       VARCHAR(128)  NULL,
    payload_type      VARCHAR(255)  NULL,
    payload           MEDIUMBLOB    NULL,
    attributes        TEXT          NULL,
    created_at        TIMESTAMP(6)  DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    completed_at      TIMESTAMP(6)  NULL,
    expires_at        TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);
