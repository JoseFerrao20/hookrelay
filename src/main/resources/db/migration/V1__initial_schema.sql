CREATE TABLE endpoints (
    id          UUID PRIMARY KEY,
    url         VARCHAR(2048) NOT NULL,
    description VARCHAR(500),
    secret      VARCHAR(100)  NOT NULL,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE endpoint_event_types (
    endpoint_id UUID         NOT NULL REFERENCES endpoints (id) ON DELETE CASCADE,
    event_type  VARCHAR(100) NOT NULL,
    PRIMARY KEY (endpoint_id, event_type)
);

CREATE INDEX idx_endpoint_event_types_type ON endpoint_event_types (event_type);

CREATE TABLE events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_events_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_events_type_created ON events (event_type, created_at DESC);
CREATE INDEX idx_events_created ON events (created_at DESC);

CREATE TABLE deliveries (
    id                   UUID PRIMARY KEY,
    event_id             UUID        NOT NULL REFERENCES events (id),
    endpoint_id          UUID        NOT NULL REFERENCES endpoints (id),
    status               VARCHAR(20) NOT NULL,
    attempt_count        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ,
    last_attempt_at      TIMESTAMPTZ,
    last_response_status INTEGER,
    last_error           VARCHAR(2000),
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_deliveries_event_endpoint UNIQUE (event_id, endpoint_id),
    CONSTRAINT ck_deliveries_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'DEAD'))
);

CREATE INDEX idx_deliveries_status_next_attempt ON deliveries (status, next_attempt_at);
CREATE INDEX idx_deliveries_endpoint_created ON deliveries (endpoint_id, created_at DESC);
CREATE INDEX idx_deliveries_event ON deliveries (event_id);
