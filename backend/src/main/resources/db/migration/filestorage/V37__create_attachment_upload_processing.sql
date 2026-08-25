CREATE TABLE yumpoo.attachment (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    quota_project_id uuid NOT NULL,
    owner_type varchar(32) NOT NULL,
    owner_id uuid NOT NULL,
    original_file_name varchar(255) NOT NULL,
    file_extension varchar(16) NOT NULL,
    declared_mime varchar(160) NOT NULL,
    detected_mime varchar(160),
    size_bytes bigint,
    sha256 char(64),
    storage_key varchar(160),
    status varchar(16) NOT NULL,
    processing_stage varchar(24),
    rejected_code varchar(40),
    reserved_bytes bigint NOT NULL,
    uploaded_by_user_id uuid NOT NULL,
    intent_expires_at timestamptz NOT NULL,
    sealed_at timestamptz,
    quarantine_retain_until timestamptz,
    available_at timestamptz,
    rejected_at timestamptz,
    upload_lease_token uuid,
    upload_lease_until timestamptz,
    scan_generation integer NOT NULL DEFAULT 0,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_attachment_company_id UNIQUE (id, company_id),
    CONSTRAINT ck_attachment_owner_type CHECK (
        owner_type IN ('WORK_ITEM', 'WORK_ITEM_UPDATE', 'PRODUCT_FEEDBACK', 'FEEDBACK_UPDATE')
    ),
    CONSTRAINT ck_attachment_status CHECK (status IN ('UPLOADING', 'AVAILABLE', 'REJECTED', 'DELETED')),
    CONSTRAINT ck_attachment_processing_stage CHECK (
        processing_stage IS NULL OR processing_stage IN ('RECEIVING', 'QUEUED_SCAN', 'SCANNING', 'FINALIZING')
    ),
    CONSTRAINT ck_attachment_rejected_code CHECK (
        rejected_code IS NULL OR rejected_code IN (
            'FILE_TOO_LARGE', 'FILE_TYPE_NOT_ALLOWED', 'MALWARE_DETECTED',
            'SCAN_UNAVAILABLE', 'UPLOAD_INCOMPLETE', 'INTEGRITY_CHECK_FAILED',
            'PARENT_NOT_WRITABLE', 'QUOTA_EXCEEDED'
        )
    ),
    CONSTRAINT ck_attachment_nonnegative CHECK (
        reserved_bytes >= 0 AND (size_bytes IS NULL OR size_bytes >= 0)
        AND scan_generation >= 0 AND row_version >= 0
    ),
    CONSTRAINT ck_attachment_hash CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attachment_upload_lease CHECK (
        (upload_lease_token IS NULL AND upload_lease_until IS NULL)
        OR (upload_lease_token IS NOT NULL AND upload_lease_until IS NOT NULL)
    ),
    CONSTRAINT ck_attachment_available CHECK (
        status <> 'AVAILABLE'
        OR (size_bytes IS NOT NULL AND size_bytes > 0 AND sha256 IS NOT NULL
            AND detected_mime IS NOT NULL AND storage_key IS NOT NULL
            AND available_at IS NOT NULL AND rejected_code IS NULL)
    ),
    CONSTRAINT ck_attachment_rejected CHECK (
        status <> 'REJECTED' OR (rejected_code IS NOT NULL AND rejected_at IS NOT NULL)
    )
);

CREATE INDEX idx_attachment_owner_page
    ON yumpoo.attachment (company_id, owner_type, owner_id, created_at DESC, id DESC);
CREATE INDEX idx_attachment_upload_lease
    ON yumpoo.attachment (upload_lease_until) WHERE upload_lease_token IS NOT NULL;

CREATE TABLE yumpoo.attachment_scan_task (
    id uuid PRIMARY KEY,
    attachment_id uuid NOT NULL,
    company_id uuid NOT NULL,
    generation integer NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(160),
    lease_token uuid,
    lease_until timestamptz,
    final_result varchar(40),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_attachment_scan_generation UNIQUE (attachment_id, generation),
    CONSTRAINT fk_attachment_scan_attachment FOREIGN KEY (attachment_id, company_id)
        REFERENCES yumpoo.attachment (id, company_id),
    CONSTRAINT ck_attachment_scan_status CHECK (status IN ('READY', 'RUNNING', 'COMPLETED')),
    CONSTRAINT ck_attachment_scan_attempt CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT ck_attachment_scan_lease CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX idx_attachment_scan_claim
    ON yumpoo.attachment_scan_task (next_attempt_at, created_at, id)
    WHERE status IN ('READY', 'RUNNING');

CREATE TABLE yumpoo.attachment_quota_usage (
    company_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid NOT NULL,
    reserved_bytes bigint NOT NULL DEFAULT 0,
    available_bytes bigint NOT NULL DEFAULT 0,
    row_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (company_id, scope_type, scope_id),
    CONSTRAINT ck_attachment_quota_scope CHECK (scope_type IN ('COMPANY', 'PROJECT')),
    CONSTRAINT ck_attachment_quota_nonnegative CHECK (
        reserved_bytes >= 0 AND available_bytes >= 0 AND row_version >= 0
    )
);

COMMENT ON TABLE yumpoo.attachment IS
    'File-storage owned attachment metadata; polymorphic owner references are authorized through public parent ports.';
COMMENT ON TABLE yumpoo.attachment_scan_task IS
    'Restart-safe leased attachment scanning queue. File scanning never runs inside the claiming transaction.';
