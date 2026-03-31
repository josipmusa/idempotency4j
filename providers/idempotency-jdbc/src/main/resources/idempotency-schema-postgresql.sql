CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key   VARCHAR(255)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    locked_at         TIMESTAMP(6)  NULL,
    lock_expires_at   TIMESTAMP(6)  NULL,
    response_code     INT           NULL,
    response_headers  TEXT          NULL,
    response_body     BYTEA         NULL,
    request_fingerprint VARCHAR(64) NULL,
    completed_at      TIMESTAMP(6)  NULL,
    created_at        TIMESTAMP(6)  DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at        TIMESTAMP(6)  NULL,
    PRIMARY KEY (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_status_expires
    ON idempotency_records (status, expires_at, lock_expires_at);
