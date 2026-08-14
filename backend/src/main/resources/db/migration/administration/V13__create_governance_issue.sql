CREATE TABLE yumpoo.governance_issue (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    issue_type varchar(64) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    safe_summary_code varchar(80) NOT NULL,
    detected_event_id uuid NOT NULL,
    detected_at timestamptz NOT NULL,
    resolved_event_id uuid,
    resolved_at timestamptz,
    resolution_code varchar(80),
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_governance_issue_company
        FOREIGN KEY (company_id) REFERENCES yumpoo.company (id) ON DELETE CASCADE,
    CONSTRAINT uq_governance_issue_detected_event UNIQUE (detected_event_id),
    CONSTRAINT uq_governance_issue_resolved_event UNIQUE (resolved_event_id),
    CONSTRAINT ck_governance_issue_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_governance_issue_type CHECK (issue_type = 'APP_MANAGER_MISSING'),
    CONSTRAINT ck_governance_issue_target CHECK (
        target_type = 'COMPANY' AND target_id = company_id
    ),
    CONSTRAINT ck_governance_issue_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_governance_issue_summary CHECK (
        safe_summary_code ~ '^[A-Z][A-Z0-9_]{1,79}$'
    ),
    CONSTRAINT ck_governance_issue_resolution CHECK (
        (status = 'OPEN' AND resolved_event_id IS NULL
            AND resolved_at IS NULL AND resolution_code IS NULL)
        OR (status = 'RESOLVED' AND resolved_event_id IS NOT NULL
            AND resolved_at IS NOT NULL
            AND resolution_code ~ '^[A-Z][A-Z0-9_]{1,79}$')
    ),
    CONSTRAINT ck_governance_issue_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_governance_issue_timestamps CHECK (
        updated_at >= created_at
        AND detected_at BETWEEN created_at AND updated_at
        AND (resolved_at IS NULL OR resolved_at BETWEEN detected_at AND updated_at)
    )
);

CREATE UNIQUE INDEX uq_governance_issue_open_target
    ON yumpoo.governance_issue (company_id, issue_type, target_type, target_id)
    WHERE status = 'OPEN';

CREATE INDEX idx_governance_issue_company_status_detected
    ON yumpoo.governance_issue (company_id, status, detected_at DESC, id);

COMMENT ON TABLE yumpoo.governance_issue IS
    'Administration projection of durable governance facts; never an authorization source.';
