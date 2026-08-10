CREATE TABLE yumpoo.outbox_event (
    event_id uuid PRIMARY KEY,
    event_type varchar(120) NOT NULL,
    event_version smallint NOT NULL,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL,
    company_id uuid NOT NULL,
    actor_type varchar(20) NOT NULL,
    actor_user_id uuid NULL,
    actor_system_code varchar(80) NULL,
    actor_reason_reference varchar(160) NULL,
    occurred_at timestamptz NOT NULL,
    request_id varchar(64) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    causation_id uuid NULL,
    payload_json jsonb NOT NULL,
    status varchar(20) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NULL,
    lease_owner varchar(80) NULL,
    lease_token uuid NULL,
    lease_until timestamptz NULL,
    last_error_consumer varchar(120) NULL,
    last_error_code varchar(80) NULL,
    last_error_type varchar(160) NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    dead_at timestamptz NULL,
    CONSTRAINT uq_outbox_event_aggregate_fact UNIQUE (
        aggregate_type,
        aggregate_id,
        aggregate_version,
        event_type
    ),
    CONSTRAINT ck_outbox_event_id_v4 CHECK (
        event_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_outbox_event_type CHECK (
        event_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'
    ),
    CONSTRAINT ck_outbox_event_version CHECK (event_version > 0),
    CONSTRAINT ck_outbox_event_aggregate_type CHECK (
        length(btrim(aggregate_type)) BETWEEN 1 AND 80
    ),
    CONSTRAINT ck_outbox_event_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_outbox_event_actor CHECK (
        (
            actor_type = 'USER'
            AND actor_user_id IS NOT NULL
            AND actor_system_code IS NULL
            AND actor_reason_reference IS NULL
        )
        OR (
            actor_type = 'SYSTEM'
            AND actor_user_id IS NULL
            AND actor_system_code ~ '^[A-Z][A-Z0-9_.:-]*$'
            AND actor_reason_reference IS NULL
        )
        OR (
            actor_type = 'ADMIN_OVERRIDE'
            AND actor_user_id IS NOT NULL
            AND actor_system_code IS NULL
            AND length(btrim(actor_reason_reference)) BETWEEN 1 AND 160
        )
    ),
    CONSTRAINT ck_outbox_event_request_id CHECK (
        request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    CONSTRAINT ck_outbox_event_correlation_id CHECK (
        correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    CONSTRAINT ck_outbox_event_payload CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_outbox_event_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY', 'COMPLETED', 'DEAD')
    ),
    CONSTRAINT ck_outbox_event_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_event_last_error CHECK (
        (
            last_error_consumer IS NULL
            AND last_error_code IS NULL
            AND last_error_type IS NULL
        )
        OR (
            last_error_consumer ~ '^[a-z][a-z0-9_.:-]{0,119}$'
            AND last_error_code ~ '^[A-Z][A-Z0-9_]{0,79}$'
            AND length(btrim(last_error_type)) BETWEEN 1 AND 160
        )
    ),
    CONSTRAINT ck_outbox_event_lifecycle CHECK (
        (
            status IN ('PENDING', 'RETRY')
            AND next_attempt_at IS NOT NULL
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND completed_at IS NULL
            AND dead_at IS NULL
        )
        OR (
            status = 'PROCESSING'
            AND next_attempt_at IS NULL
            AND length(btrim(lease_owner)) BETWEEN 1 AND 80
            AND lease_token IS NOT NULL
            AND lease_until IS NOT NULL
            AND completed_at IS NULL
            AND dead_at IS NULL
        )
        OR (
            status = 'COMPLETED'
            AND next_attempt_at IS NULL
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND completed_at IS NOT NULL
            AND dead_at IS NULL
        )
        OR (
            status = 'DEAD'
            AND next_attempt_at IS NULL
            AND lease_owner IS NULL
            AND lease_token IS NULL
            AND lease_until IS NULL
            AND completed_at IS NULL
            AND dead_at IS NOT NULL
            AND last_error_code IS NOT NULL
        )
    ),
    CONSTRAINT ck_outbox_event_times CHECK (
        created_at = occurred_at
        AND (next_attempt_at IS NULL OR next_attempt_at >= occurred_at)
        AND (lease_until IS NULL OR lease_until >= occurred_at)
        AND (completed_at IS NULL OR completed_at >= occurred_at)
        AND (dead_at IS NULL OR dead_at >= occurred_at)
    )
);

CREATE INDEX idx_outbox_event_available
    ON yumpoo.outbox_event (status, next_attempt_at, occurred_at, event_id);

CREATE INDEX idx_outbox_event_expired_lease
    ON yumpoo.outbox_event (lease_until, occurred_at, event_id)
    WHERE status = 'PROCESSING';

CREATE INDEX idx_outbox_event_aggregate_order
    ON yumpoo.outbox_event (aggregate_type, aggregate_id, aggregate_version, event_id);

CREATE TABLE yumpoo.outbox_consumer_receipt (
    consumer_name varchar(120) NOT NULL,
    event_id uuid NOT NULL,
    completed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT fk_outbox_consumer_receipt_event
        FOREIGN KEY (event_id)
        REFERENCES yumpoo.outbox_event (event_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_outbox_consumer_receipt_name CHECK (
        consumer_name ~ '^[a-z][a-z0-9_.:-]{0,119}$'
    )
);

CREATE INDEX idx_outbox_consumer_receipt_event
    ON yumpoo.outbox_consumer_receipt (event_id);

COMMENT ON TABLE yumpoo.outbox_event IS
    'Transactional domain events with lease-based at-least-once dispatch lifecycle';

COMMENT ON TABLE yumpoo.outbox_consumer_receipt IS
    'Per-consumer durable completion receipts used to suppress duplicate database effects';
