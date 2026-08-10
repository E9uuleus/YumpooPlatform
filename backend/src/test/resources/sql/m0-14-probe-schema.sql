CREATE SCHEMA IF NOT EXISTS yumpoo;

CREATE TABLE yumpoo.m014_attachment_probe (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    uploader_actor uuid NOT NULL,
    original_file_name varchar(255) NOT NULL,
    declared_mime varchar(127) NOT NULL,
    status varchar(16) NOT NULL,
    processing_stage varchar(24) NULL,
    size_bytes bigint NULL,
    sha256 char(64) NULL,
    detected_mime varchar(127) NULL,
    quarantine_path text NULL,
    storage_key varchar(96) NULL,
    rejected_code varchar(64) NULL,
    last_failure_code varchar(64) NULL,
    row_version bigint NOT NULL DEFAULT 0,
    probe_observation bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_m014_probe_status CHECK (
        status IN ('UPLOADING', 'AVAILABLE', 'REJECTED')
    ),
    CONSTRAINT ck_m014_probe_stage CHECK (
        processing_stage IS NULL OR processing_stage IN (
            'RECEIVING', 'QUEUED_SCAN', 'SCANNING', 'FINALIZING'
        )
    ),
    CONSTRAINT ck_m014_probe_size CHECK (
        size_bytes IS NULL OR size_bytes BETWEEN 1 AND 104857600
    ),
    CONSTRAINT ck_m014_probe_sha256 CHECK (
        sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_m014_probe_version CHECK (
        row_version >= 0 AND probe_observation >= 0
    ),
    CONSTRAINT ck_m014_probe_available CHECK (
        status <> 'AVAILABLE' OR (
            processing_stage IS NULL
            AND size_bytes IS NOT NULL
            AND sha256 IS NOT NULL
            AND detected_mime IS NOT NULL
            AND storage_key IS NOT NULL
            AND quarantine_path IS NULL
            AND rejected_code IS NULL
        )
    ),
    CONSTRAINT ck_m014_probe_rejected CHECK (
        status <> 'REJECTED' OR (
            processing_stage IS NULL
            AND rejected_code IS NOT NULL
            AND storage_key IS NULL
        )
    )
);
