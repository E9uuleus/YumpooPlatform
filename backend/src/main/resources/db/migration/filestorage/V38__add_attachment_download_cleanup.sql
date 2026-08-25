ALTER TABLE yumpoo.attachment
    ADD COLUMN deleted_by_user_id uuid,
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN delete_reason varchar(500);

ALTER TABLE yumpoo.attachment
    ADD CONSTRAINT ck_attachment_deleted CHECK (
        (status = 'DELETED'
            AND deleted_by_user_id IS NOT NULL
            AND deleted_at IS NOT NULL
            AND delete_reason IS NOT NULL
            AND length(btrim(delete_reason)) BETWEEN 1 AND 500)
        OR
        (status <> 'DELETED'
            AND deleted_by_user_id IS NULL
            AND deleted_at IS NULL
            AND delete_reason IS NULL)
    );

CREATE INDEX idx_attachment_expired_intent
    ON yumpoo.attachment (intent_expires_at, id)
    WHERE status = 'UPLOADING';
CREATE INDEX idx_attachment_blob_reference
    ON yumpoo.attachment (storage_key, status, id)
    WHERE storage_key IS NOT NULL;
CREATE INDEX idx_attachment_digest_reference
    ON yumpoo.attachment (sha256, status, id)
    WHERE sha256 IS NOT NULL;

CREATE TABLE yumpoo.attachment_blob (
    storage_key varchar(160) PRIMARY KEY,
    sha256 char(64) NOT NULL UNIQUE,
    size_bytes bigint NOT NULL,
    presence_status varchar(16) NOT NULL DEFAULT 'PRESENT',
    last_verified_at timestamptz,
    orphan_first_seen_at timestamptz,
    operation_type varchar(16),
    operation_owner varchar(160),
    operation_token uuid,
    operation_lease_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_attachment_blob_key CHECK (
        storage_key ~ '^sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}$'
        AND substring(storage_key FROM 8 FOR 2) = substring(sha256 FROM 1 FOR 2)
        AND substring(storage_key FROM 11 FOR 2) = substring(sha256 FROM 3 FOR 2)
        AND right(storage_key, 64) = sha256
    ),
    CONSTRAINT ck_attachment_blob_size CHECK (size_bytes > 0 AND row_version >= 0),
    CONSTRAINT ck_attachment_blob_presence CHECK (presence_status IN ('PRESENT', 'MISSING', 'DELETED')),
    CONSTRAINT ck_attachment_blob_operation CHECK (
        (operation_type IS NULL AND operation_owner IS NULL
            AND operation_token IS NULL AND operation_lease_until IS NULL)
        OR
        (operation_type IN ('PUBLISH', 'CLEANUP') AND operation_owner IS NOT NULL
            AND operation_token IS NOT NULL AND operation_lease_until IS NOT NULL)
    )
);

INSERT INTO yumpoo.attachment_blob (
    storage_key, sha256, size_bytes, presence_status, created_at, updated_at
)
SELECT storage_key, sha256, max(size_bytes), 'PRESENT', min(created_at), max(updated_at)
  FROM yumpoo.attachment
 WHERE storage_key IS NOT NULL
 GROUP BY storage_key, sha256;

ALTER TABLE yumpoo.attachment
    ADD CONSTRAINT ck_attachment_storage_key CHECK (
        storage_key IS NULL OR storage_key ~ '^sha256/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT fk_attachment_blob FOREIGN KEY (storage_key)
        REFERENCES yumpoo.attachment_blob (storage_key);

CREATE INDEX idx_attachment_blob_lease
    ON yumpoo.attachment_blob (operation_lease_until)
    WHERE operation_token IS NOT NULL;
CREATE INDEX idx_attachment_blob_orphan
    ON yumpoo.attachment_blob (orphan_first_seen_at, storage_key)
    WHERE orphan_first_seen_at IS NOT NULL;

CREATE TABLE yumpoo.attachment_maintenance_run (
    id uuid PRIMARY KEY,
    status varchar(16) NOT NULL,
    phase varchar(32) NOT NULL,
    cursor_value varchar(320),
    dry_run boolean NOT NULL,
    approval_reference varchar(500),
    lease_owner varchar(160),
    lease_token uuid,
    lease_until timestamptz,
    expired_intents bigint NOT NULL DEFAULT 0,
    temporary_candidates bigint NOT NULL DEFAULT 0,
    verified_blobs bigint NOT NULL DEFAULT 0,
    orphan_candidates bigint NOT NULL DEFAULT 0,
    deleted_files bigint NOT NULL DEFAULT 0,
    issue_count bigint NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_attachment_maintenance_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_attachment_maintenance_phase CHECK (
        phase IN ('EXPIRE_INTENTS', 'TEMPORARY_FILES', 'VERIFY_BLOBS',
                  'RECONCILE_QUOTAS', 'RECONCILE_SCANS', 'ORPHANS', 'COMPLETED')
    ),
    CONSTRAINT ck_attachment_maintenance_lease CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_attachment_maintenance_counts CHECK (
        expired_intents >= 0 AND temporary_candidates >= 0 AND verified_blobs >= 0
        AND orphan_candidates >= 0 AND deleted_files >= 0 AND issue_count >= 0
    )
);

CREATE UNIQUE INDEX uq_attachment_maintenance_running
    ON yumpoo.attachment_maintenance_run ((status)) WHERE status = 'RUNNING';
CREATE INDEX idx_attachment_maintenance_history
    ON yumpoo.attachment_maintenance_run (started_at DESC, id DESC);

CREATE TABLE yumpoo.attachment_reconciliation_issue (
    id uuid PRIMARY KEY,
    issue_code varchar(40) NOT NULL,
    subject_type varchar(24) NOT NULL,
    subject_key varchar(320) NOT NULL,
    attachment_id uuid,
    company_id uuid,
    first_detected_at timestamptz NOT NULL,
    last_detected_at timestamptz NOT NULL,
    resolved_at timestamptz,
    cleanup_eligible_at timestamptz,
    detection_count bigint NOT NULL DEFAULT 1,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_attachment_reconciliation_code CHECK (
        issue_code IN ('MISSING_BLOB', 'SIZE_MISMATCH', 'HASH_MISMATCH',
                       'PUBLISHED_ORPHAN', 'QUARANTINE_ORPHAN', 'UNEXPECTED_ENTRY',
                       'QUOTA_MISMATCH', 'STALE_SCAN_TASK')
    ),
    CONSTRAINT ck_attachment_reconciliation_subject CHECK (
        subject_type IN ('ATTACHMENT', 'BLOB', 'QUARANTINE', 'QUOTA', 'SCAN_TASK')
    ),
    CONSTRAINT ck_attachment_reconciliation_counts CHECK (detection_count > 0 AND row_version >= 0),
    CONSTRAINT fk_attachment_reconciliation_attachment FOREIGN KEY (attachment_id)
        REFERENCES yumpoo.attachment (id)
);

CREATE UNIQUE INDEX uq_attachment_reconciliation_open
    ON yumpoo.attachment_reconciliation_issue (issue_code, subject_type, subject_key)
    WHERE resolved_at IS NULL;
CREATE INDEX idx_attachment_reconciliation_active
    ON yumpoo.attachment_reconciliation_issue (issue_code, first_detected_at, id)
    WHERE resolved_at IS NULL;

COMMENT ON TABLE yumpoo.attachment_blob IS
    'Physical attachment blob registry and mutually exclusive publish/cleanup operation leases.';
COMMENT ON TABLE yumpoo.attachment_maintenance_run IS
    'Restart-safe attachment maintenance run with persisted phase, cursor and counters.';
COMMENT ON TABLE yumpoo.attachment_reconciliation_issue IS
    'Internal attachment reconciliation observations; storage keys are never exposed by public APIs.';
