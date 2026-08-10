CREATE TABLE yumpoo.idempotency_record (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL,
    http_method varchar(10) NOT NULL,
    route_key varchar(160) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    state varchar(20) NOT NULL,
    http_status integer NULL,
    response_json jsonb NULL,
    resource_id uuid NULL,
    response_etag varchar(128) NULL,
    lease_until timestamptz NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT uq_idempotency_record_scope UNIQUE (
        actor_user_id,
        http_method,
        route_key,
        idempotency_key
    ),
    CONSTRAINT ck_idempotency_record_http_method CHECK (
        length(btrim(http_method)) BETWEEN 1 AND 10
        AND http_method = upper(http_method)
    ),
    CONSTRAINT ck_idempotency_record_route_key CHECK (
        length(btrim(route_key)) BETWEEN 1 AND 160
    ),
    CONSTRAINT ck_idempotency_record_request_hash CHECK (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_idempotency_record_state CHECK (
        state IN ('PROCESSING', 'COMPLETED')
    ),
    CONSTRAINT ck_idempotency_record_http_status CHECK (
        http_status IS NULL OR http_status BETWEEN 200 AND 299
    ),
    CONSTRAINT ck_idempotency_record_response_etag CHECK (
        response_etag IS NULL OR (
            length(response_etag) <= 128
            AND CASE
                WHEN response_etag ~ '^"[0-9]+"$' THEN
                    substring(response_etag FROM 2 FOR length(response_etag) - 2)::numeric
                        <= 9223372036854775807
                ELSE FALSE
            END
        )
    ),
    CONSTRAINT ck_idempotency_record_lease CHECK (
        lease_until IS NULL OR lease_until >= created_at
    ),
    CONSTRAINT ck_idempotency_record_completed_at CHECK (
        completed_at IS NULL OR completed_at >= created_at
    ),
    CONSTRAINT ck_idempotency_record_expires_at CHECK (
        expires_at > created_at
    ),
    CONSTRAINT ck_idempotency_record_lifecycle CHECK (
        (
            state = 'PROCESSING'
            AND lease_until IS NOT NULL
            AND http_status IS NULL
            AND response_json IS NULL
            AND resource_id IS NULL
            AND response_etag IS NULL
            AND completed_at IS NULL
        )
        OR (
            state = 'COMPLETED'
            AND lease_until IS NULL
            AND http_status IS NOT NULL
            AND response_json IS NOT NULL
            AND completed_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_idempotency_record_expires_at
    ON yumpoo.idempotency_record (expires_at);

COMMENT ON TABLE yumpoo.idempotency_record IS
    'Persistent command idempotency claims and safely replayable successful responses';

COMMENT ON COLUMN yumpoo.idempotency_record.lease_until IS
    'Reserved crash-recovery metadata; M0-10 does not perform automatic takeover';

COMMENT ON COLUMN yumpoo.idempotency_record.expires_at IS
    'Reserved cleanup metadata; M0-10 does not perform automatic deletion';
