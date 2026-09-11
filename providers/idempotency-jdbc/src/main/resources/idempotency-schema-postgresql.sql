CREATE TABLE IF NOT EXISTS idempotency_records (
    scope             VARCHAR(128)  NOT NULL,
    idempotency_key   VARCHAR(255)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    lease_expires_at  TIMESTAMP(6)  NULL,
    payload_type      VARCHAR(255)  NULL,
    payload           BYTEA         NULL,
    attributes        TEXT          NULL,
    fingerprint       VARCHAR(128)  NULL,
    lease_id          VARCHAR(36)   NULL,
    completed_at      TIMESTAMP(6)  NULL,
    created_at        TIMESTAMP(6)  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at        TIMESTAMP(6)  NULL,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_status_expires
    ON idempotency_records (status, expires_at, lease_expires_at);
