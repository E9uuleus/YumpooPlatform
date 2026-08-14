CREATE TABLE yumpoo.security_audit_event (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    fact_key varchar(200) NOT NULL,
    action varchar(96) NOT NULL,
    outcome varchar(16) NOT NULL,
    actor_type varchar(16) NOT NULL,
    actor_user_id uuid,
    actor_system_code varchar(64),
    actor_role_snapshot jsonb NOT NULL DEFAULT '[]'::jsonb,
    target_type varchar(64) NOT NULL,
    target_id varchar(128) NOT NULL,
    reason_reference varchar(160),
    before_summary jsonb,
    after_summary jsonb,
    error_code varchar(64),
    command_id uuid,
    request_id varchar(64) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    client_type varchar(32),
    client_version varchar(64),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_security_audit_event_company
        FOREIGN KEY (company_id) REFERENCES yumpoo.company (id),
    CONSTRAINT fk_security_audit_event_actor_company
        FOREIGN KEY (actor_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT uq_security_audit_event_fact UNIQUE (company_id, fact_key),
    CONSTRAINT ck_security_audit_event_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_security_audit_event_action CHECK (
        action ~ '^[A-Z][A-Z0-9_]{2,95}$'
    ),
    CONSTRAINT ck_security_audit_event_outcome CHECK (
        outcome IN ('SUCCEEDED', 'FAILED', 'PARTIAL')
    ),
    CONSTRAINT ck_security_audit_event_actor CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL AND actor_system_code IS NULL)
        OR (actor_type IN ('SYSTEM', 'INTEGRATION')
            AND actor_user_id IS NULL
            AND actor_system_code ~ '^[A-Z][A-Z0-9_]{1,63}$')
        OR (actor_type = 'ANONYMOUS'
            AND actor_user_id IS NULL
            AND actor_system_code IS NULL)
    ),
    CONSTRAINT ck_security_audit_event_roles_array CHECK (
        jsonb_typeof(actor_role_snapshot) = 'array'
    ),
    CONSTRAINT ck_security_audit_event_reason CHECK (
        reason_reference IS NULL
        OR (char_length(reason_reference) BETWEEN 1 AND 160
            AND reason_reference = btrim(reason_reference))
    ),
    CONSTRAINT ck_security_audit_event_error CHECK (
        (outcome = 'SUCCEEDED' AND error_code IS NULL)
        OR (outcome IN ('FAILED', 'PARTIAL') AND error_code IS NOT NULL
            AND error_code ~ '^[A-Z][A-Z0-9_]{1,63}$')
    ),
    CONSTRAINT ck_security_audit_event_request CHECK (
        request_id ~ '^[A-Za-z0-9._:-]{1,64}$'
        AND correlation_id ~ '^[A-Za-z0-9._:-]{1,64}$'
    ),
    CONSTRAINT ck_security_audit_event_client CHECK (
        client_type IS NULL OR client_type ~ '^[A-Z][A-Z0-9_]{1,31}$'
    )
);

CREATE INDEX idx_security_audit_event_company_request
    ON yumpoo.security_audit_event (company_id, request_id, occurred_at DESC, id DESC);

COMMENT ON TABLE yumpoo.security_audit_event IS
    'Append-only Security Audit facts. Application code exposes no update or delete repository.';

COMMENT ON COLUMN yumpoo.security_audit_event.before_summary IS
    'Whitelisted minimal state summary; never request bodies, credentials, contact details or raw IP.';

COMMENT ON COLUMN yumpoo.security_audit_event.after_summary IS
    'Whitelisted minimal state summary; never exception text or third-party secrets.';
