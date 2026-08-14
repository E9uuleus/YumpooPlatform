CREATE TABLE yumpoo.directory_sync_run (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    trigger_type varchar(20) NOT NULL,
    triggered_by_user_id uuid NULL,
    trigger_key_hash char(64) NOT NULL,
    phase varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    lease_token uuid NULL,
    lease_until timestamptz NULL,
    provider_cursor varchar(4096) NULL,
    cursor_termination_mode varchar(32) NULL,
    page_count integer NOT NULL DEFAULT 0,
    member_set_hash char(64) NULL,
    page_trajectory_hash char(64) NULL,
    scan_complete boolean NOT NULL DEFAULT false,
    discovered_count integer NOT NULL DEFAULT 0,
    staged_count integer NOT NULL DEFAULT 0,
    created_count integer NOT NULL DEFAULT 0,
    updated_count integer NOT NULL DEFAULT 0,
    unchanged_count integer NOT NULL DEFAULT 0,
    left_count integer NOT NULL DEFAULT 0,
    returned_count integer NOT NULL DEFAULT 0,
    failed_count integer NOT NULL DEFAULT 0,
    not_applied_count integer NOT NULL DEFAULT 0,
    error_code varchar(80) NULL,
    error_summary varchar(500) NULL,
    request_id varchar(64) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL,
    finished_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_directory_sync_run_company FOREIGN KEY (company_id)
        REFERENCES yumpoo.company (id),
    CONSTRAINT fk_directory_sync_run_triggered_by FOREIGN KEY (
        triggered_by_user_id,
        company_id
    ) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT uq_directory_sync_run_trigger UNIQUE (company_id, trigger_key_hash),
    CONSTRAINT ck_directory_sync_run_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_directory_sync_run_trigger CHECK (
        (trigger_type = 'MANUAL' AND triggered_by_user_id IS NOT NULL)
        OR (trigger_type = 'SCHEDULED' AND triggered_by_user_id IS NULL)
    ),
    CONSTRAINT ck_directory_sync_run_trigger_hash CHECK (
        trigger_key_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_directory_sync_run_phase CHECK (
        phase IN (
            'COLLECTING_IDS',
            'COLLECTING_PROFILES',
            'APPLYING',
            'FINALIZING',
            'COMPLETED'
        )
    ),
    CONSTRAINT ck_directory_sync_run_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_directory_sync_run_lease CHECK (
        (status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_directory_sync_run_cursor CHECK (
        provider_cursor IS NULL OR length(provider_cursor) BETWEEN 1 AND 4096
    ),
    CONSTRAINT ck_directory_sync_run_termination CHECK (
        cursor_termination_mode IS NULL
        OR cursor_termination_mode IN ('EXPLICIT_EMPTY', 'OMITTED_CONFIRMED')
    ),
    CONSTRAINT ck_directory_sync_run_hashes CHECK (
        (member_set_hash IS NULL OR member_set_hash ~ '^[0-9a-f]{64}$')
        AND (
            page_trajectory_hash IS NULL
            OR page_trajectory_hash ~ '^[0-9a-f]{64}$'
        )
    ),
    CONSTRAINT ck_directory_sync_run_counts CHECK (
        page_count >= 0
        AND discovered_count >= 0
        AND staged_count >= 0
        AND created_count >= 0
        AND updated_count >= 0
        AND unchanged_count >= 0
        AND left_count >= 0
        AND returned_count >= 0
        AND failed_count >= 0
        AND not_applied_count >= 0
        AND created_count + updated_count + unchanged_count + failed_count
            + not_applied_count <= discovered_count
    ),
    CONSTRAINT ck_directory_sync_run_error CHECK (
        (error_code IS NULL AND error_summary IS NULL)
        OR (
            error_code IS NOT NULL
            AND error_code ~ '^[A-Z][A-Z0-9_]{0,79}$'
            AND error_summary IS NOT NULL
            AND error_summary = btrim(error_summary)
            AND length(error_summary) BETWEEN 1 AND 500
        )
    ),
    CONSTRAINT ck_directory_sync_run_request_id CHECK (
        request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    CONSTRAINT ck_directory_sync_run_lifecycle CHECK (
        (
            status = 'RUNNING'
            AND finished_at IS NULL
            AND phase <> 'COMPLETED'
        ) OR (
            status <> 'RUNNING'
            AND finished_at IS NOT NULL
            AND phase = 'COMPLETED'
            AND provider_cursor IS NULL
        )
    ),
    CONSTRAINT ck_directory_sync_run_timestamps CHECK (
        updated_at >= created_at
        AND started_at >= created_at
        AND (finished_at IS NULL OR finished_at BETWEEN started_at AND updated_at)
    ),
    CONSTRAINT ck_directory_sync_run_row_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uq_directory_sync_run_company_running
    ON yumpoo.directory_sync_run (company_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_directory_sync_run_company_started
    ON yumpoo.directory_sync_run (company_id, started_at DESC, id DESC);

CREATE TABLE yumpoo.directory_sync_item (
    run_id uuid NOT NULL,
    external_user_id varchar(256) NOT NULL,
    profile_hash char(64) NULL,
    user_id uuid NULL,
    action varchar(20) NOT NULL,
    result varchar(20) NOT NULL,
    error_code varchar(80) NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT directory_sync_item_pkey PRIMARY KEY (run_id, external_user_id),
    CONSTRAINT fk_directory_sync_item_run FOREIGN KEY (run_id)
        REFERENCES yumpoo.directory_sync_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_directory_sync_item_user FOREIGN KEY (user_id)
        REFERENCES yumpoo.identity_user (id),
    CONSTRAINT ck_directory_sync_item_external_id CHECK (
        external_user_id = btrim(external_user_id)
        AND length(external_user_id) BETWEEN 1 AND 256
    ),
    CONSTRAINT ck_directory_sync_item_profile_hash CHECK (
        profile_hash IS NULL OR profile_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_directory_sync_item_action CHECK (action = 'PROVISION'),
    CONSTRAINT ck_directory_sync_item_result CHECK (
        result IN ('PENDING', 'CREATED', 'UPDATED', 'UNCHANGED', 'FAILED', 'NOT_APPLIED')
    ),
    CONSTRAINT ck_directory_sync_item_outcome CHECK (
        (result = 'PENDING' AND user_id IS NULL AND error_code IS NULL)
        OR (result IN ('CREATED', 'UPDATED', 'UNCHANGED') AND user_id IS NOT NULL AND error_code IS NULL)
        OR (result IN ('FAILED', 'NOT_APPLIED') AND user_id IS NULL AND error_code IS NOT NULL)
    ),
    CONSTRAINT ck_directory_sync_item_error CHECK (
        error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,79}$'
    ),
    CONSTRAINT ck_directory_sync_item_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE yumpoo.directory_sync_staging_member (
    run_id uuid NOT NULL,
    external_user_id varchar(256) NOT NULL,
    first_scan_seen boolean NOT NULL DEFAULT false,
    second_scan_seen boolean NOT NULL DEFAULT false,
    display_name varchar(200) NULL,
    email_state varchar(20) NULL,
    email varchar(320) NULL,
    mobile_state varchar(20) NULL,
    mobile varchar(64) NULL,
    department_summary varchar(1000) NULL,
    profile_hash char(64) NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT directory_sync_staging_member_pkey PRIMARY KEY (
        run_id,
        external_user_id
    ),
    CONSTRAINT fk_directory_sync_staging_run FOREIGN KEY (run_id)
        REFERENCES yumpoo.directory_sync_run (id) ON DELETE CASCADE,
    CONSTRAINT ck_directory_sync_staging_external_id CHECK (
        external_user_id = btrim(external_user_id)
        AND length(external_user_id) BETWEEN 1 AND 256
    ),
    CONSTRAINT ck_directory_sync_staging_display_name CHECK (
        display_name IS NULL
        OR (display_name = btrim(display_name) AND length(display_name) BETWEEN 1 AND 200)
    ),
    CONSTRAINT ck_directory_sync_staging_email CHECK (
        email_state IS NULL
        OR (
            email_state = 'PRESENT'
            AND email IS NOT NULL
            AND email = btrim(email)
            AND length(email) BETWEEN 1 AND 320
        )
        OR (email_state IN ('CLEAR', 'UNAVAILABLE') AND email IS NULL)
    ),
    CONSTRAINT ck_directory_sync_staging_mobile CHECK (
        mobile_state IS NULL
        OR (
            mobile_state = 'PRESENT'
            AND mobile IS NOT NULL
            AND mobile = btrim(mobile)
            AND length(mobile) BETWEEN 1 AND 64
        )
        OR (mobile_state IN ('CLEAR', 'UNAVAILABLE') AND mobile IS NULL)
    ),
    CONSTRAINT ck_directory_sync_staging_department CHECK (
        department_summary IS NULL
        OR (
            department_summary = btrim(department_summary)
            AND length(department_summary) BETWEEN 1 AND 1000
        )
    ),
    CONSTRAINT ck_directory_sync_staging_profile_hash CHECK (
        profile_hash IS NULL OR profile_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_directory_sync_staging_profile_complete CHECK (
        (display_name IS NULL AND email_state IS NULL AND mobile_state IS NULL AND profile_hash IS NULL)
        OR (display_name IS NOT NULL AND email_state IS NOT NULL AND mobile_state IS NOT NULL AND profile_hash IS NOT NULL)
    ),
    CONSTRAINT ck_directory_sync_staging_timestamps CHECK (updated_at >= created_at)
);

COMMENT ON TABLE yumpoo.directory_sync_run IS
    'Auditable WeCom directory synchronization batch owned by identityaccess';
COMMENT ON TABLE yumpoo.directory_sync_item IS
    'Long-lived non-profile member outcome for a directory synchronization run';
COMMENT ON TABLE yumpoo.directory_sync_staging_member IS
    'RUNNING-only normalized profile staging; deleted when the run reaches a terminal state';
