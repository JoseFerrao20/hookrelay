ALTER TABLE deliveries ADD COLUMN locked_until TIMESTAMPTZ;

CREATE INDEX idx_deliveries_due ON deliveries (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE delivery_attempts (
    id              UUID PRIMARY KEY,
    delivery_id     UUID        NOT NULL REFERENCES deliveries (id),
    attempt_number  INTEGER     NOT NULL,
    outcome         VARCHAR(20) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    duration_ms     BIGINT      NOT NULL,
    response_status INTEGER,
    error           VARCHAR(2000),
    CONSTRAINT uq_delivery_attempts_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT ck_delivery_attempts_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED'))
);
