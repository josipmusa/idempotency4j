-- MySQL dialect. For PostgreSQL, replace:
--   BLOB          -> BYTEA
--   CURRENT_TIMESTAMP(6)  -> CURRENT_TIMESTAMP
CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key   VARCHAR(255)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    locked_at         TIMESTAMP(6)  NULL,
    lock_expires_at   TIMESTAMP(6)  NULL,
    response_code     INT           NULL,
    response_headers  TEXT          NULL,
    response_body     BLOB          NULL,
    completed_at      TIMESTAMP(6)  NULL,
    created_at        TIMESTAMP(6)  DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    expires_at        TIMESTAMP(6)  NULL,
    PRIMARY KEY (idempotency_key)
);
