CREATE SCHEMA IF NOT EXISTS yumpoo;

CREATE TABLE yumpoo.m013_probe_run (
    id uuid PRIMARY KEY,
    status varchar(24) NOT NULL,
    scan_complete boolean NOT NULL DEFAULT false,
    discovered_count integer NOT NULL DEFAULT 0,
    created_count integer NOT NULL DEFAULT 0,
    unchanged_count integer NOT NULL DEFAULT 0,
    returned_count integer NOT NULL DEFAULT 0,
    left_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL,
    finished_at timestamptz NULL,
    CONSTRAINT ck_m013_probe_run_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_m013_probe_run_counts CHECK (
        discovered_count >= 0
        AND created_count >= 0
        AND unchanged_count >= 0
        AND returned_count >= 0
        AND left_count >= 0
        AND failed_count >= 0
        AND created_count + unchanged_count + returned_count + failed_count
            <= discovered_count
    ),
    CONSTRAINT ck_m013_probe_run_lifecycle CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL)
    )
);

CREATE TABLE yumpoo.m013_probe_member (
    id uuid PRIMARY KEY,
    member_fingerprint char(64) NOT NULL,
    employment_status varchar(8) NOT NULL,
    first_seen_run_id uuid NOT NULL,
    last_seen_run_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_m013_probe_member_fingerprint UNIQUE (member_fingerprint),
    CONSTRAINT fk_m013_probe_member_first_seen_run FOREIGN KEY (first_seen_run_id)
        REFERENCES yumpoo.m013_probe_run (id),
    CONSTRAINT fk_m013_probe_member_last_seen_run FOREIGN KEY (last_seen_run_id)
        REFERENCES yumpoo.m013_probe_run (id),
    CONSTRAINT ck_m013_probe_member_fingerprint CHECK (
        member_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_m013_probe_member_employment_status CHECK (
        employment_status IN ('ACTIVE', 'LEFT')
    )
);

CREATE TABLE yumpoo.m013_probe_seen (
    run_id uuid NOT NULL,
    member_fingerprint char(64) NOT NULL,
    result varchar(16) NOT NULL,
    member_id uuid NULL,
    error_code varchar(64) NULL,
    PRIMARY KEY (run_id, member_fingerprint),
    CONSTRAINT fk_m013_probe_seen_run FOREIGN KEY (run_id)
        REFERENCES yumpoo.m013_probe_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_m013_probe_seen_member FOREIGN KEY (member_id)
        REFERENCES yumpoo.m013_probe_member (id),
    CONSTRAINT ck_m013_probe_seen_fingerprint CHECK (
        member_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_m013_probe_seen_result CHECK (
        result IN ('STAGED', 'CREATED', 'UNCHANGED', 'RETURNED', 'FAILED')
    ),
    CONSTRAINT ck_m013_probe_seen_outcome CHECK (
        (result = 'STAGED' AND member_id IS NULL AND error_code IS NULL)
        OR (result IN ('CREATED', 'UNCHANGED', 'RETURNED')
            AND member_id IS NOT NULL AND error_code IS NULL)
        OR (result = 'FAILED' AND member_id IS NULL AND error_code IS NOT NULL)
    )
);
