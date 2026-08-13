CREATE TABLE yumpoo.identity_user (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    employment_status varchar(20) NOT NULL,
    account_status varchar(20) NOT NULL,
    display_name varchar(200) NOT NULL,
    email varchar(320) NULL,
    mobile varchar(64) NULL,
    department_summary varchar(1000) NULL,
    directory_synced_at timestamptz NOT NULL,
    left_at timestamptz NULL,
    left_reason varchar(500) NULL,
    account_disabled_at timestamptz NULL,
    account_disabled_by_user_id uuid NULL,
    account_disabled_reason varchar(500) NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_identity_user_id_company UNIQUE (id, company_id),
    CONSTRAINT fk_identity_user_company FOREIGN KEY (company_id)
        REFERENCES yumpoo.company (id),
    CONSTRAINT fk_identity_user_disabled_by FOREIGN KEY (
        account_disabled_by_user_id,
        company_id
    ) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_identity_user_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_identity_user_employment_status CHECK (
        employment_status IN ('ACTIVE', 'LEFT')
    ),
    CONSTRAINT ck_identity_user_account_status CHECK (
        account_status IN ('ENABLED', 'DISABLED')
    ),
    CONSTRAINT ck_identity_user_display_name CHECK (
        display_name = btrim(display_name) AND length(display_name) BETWEEN 1 AND 200
    ),
    CONSTRAINT ck_identity_user_email CHECK (
        email IS NULL OR (email = btrim(email) AND length(email) BETWEEN 1 AND 320)
    ),
    CONSTRAINT ck_identity_user_mobile CHECK (
        mobile IS NULL OR (mobile = btrim(mobile) AND length(mobile) BETWEEN 1 AND 64)
    ),
    CONSTRAINT ck_identity_user_department_summary CHECK (
        department_summary IS NULL OR (
            department_summary = btrim(department_summary)
            AND length(department_summary) BETWEEN 1 AND 1000
        )
    ),
    CONSTRAINT ck_identity_user_left_facts CHECK (
        (
            left_at IS NULL
            AND left_reason IS NULL
            AND employment_status = 'ACTIVE'
        ) OR (
            left_at IS NOT NULL
            AND left_reason IS NOT NULL
            AND left_reason = btrim(left_reason)
            AND length(left_reason) BETWEEN 1 AND 500
        )
    ),
    CONSTRAINT ck_identity_user_disabled_facts CHECK (
        (
            account_disabled_at IS NULL
            AND account_disabled_by_user_id IS NULL
            AND account_disabled_reason IS NULL
            AND account_status = 'ENABLED'
        ) OR (
            account_disabled_at IS NOT NULL
            AND account_disabled_by_user_id IS NOT NULL
            AND account_disabled_reason IS NOT NULL
            AND account_disabled_reason = btrim(account_disabled_reason)
            AND length(account_disabled_reason) BETWEEN 1 AND 500
        )
    ),
    CONSTRAINT ck_identity_user_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_identity_user_timestamps CHECK (
        updated_at >= created_at
        AND directory_synced_at BETWEEN created_at AND updated_at
        AND (left_at IS NULL OR left_at BETWEEN created_at AND updated_at)
        AND (
            account_disabled_at IS NULL
            OR account_disabled_at BETWEEN created_at AND updated_at
        )
    )
);

CREATE INDEX idx_identity_user_company_status_created
    ON yumpoo.identity_user (
        company_id,
        employment_status,
        account_status,
        created_at,
        id
    );

CREATE TABLE yumpoo.external_identity (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    provider varchar(20) NOT NULL,
    external_user_id varchar(256) NOT NULL,
    provider_employment_status varchar(20) NOT NULL,
    raw_profile_hash char(64) NOT NULL,
    last_seen_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_external_identity_user_company FOREIGN KEY (user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT uq_external_identity_provider_member UNIQUE (
        company_id,
        provider,
        external_user_id
    ),
    CONSTRAINT uq_external_identity_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_external_identity_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_external_identity_provider CHECK (provider = 'WECOM'),
    CONSTRAINT ck_external_identity_external_user_id CHECK (
        external_user_id = btrim(external_user_id)
        AND length(external_user_id) BETWEEN 1 AND 256
    ),
    CONSTRAINT ck_external_identity_employment_status CHECK (
        provider_employment_status IN ('ACTIVE', 'LEFT')
    ),
    CONSTRAINT ck_external_identity_raw_profile_hash CHECK (
        raw_profile_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_external_identity_timestamps CHECK (
        updated_at >= created_at
        AND last_seen_at BETWEEN created_at AND updated_at
    )
);

COMMENT ON TABLE yumpoo.identity_user IS
    'Long-lived local member identity owned by identityaccess';
COMMENT ON TABLE yumpoo.external_identity IS
    'Strict one-to-one mapping between a local User and a WECOM member identity';
COMMENT ON COLUMN yumpoo.external_identity.external_user_id IS
    'Case-preserving stable WECOM member identifier; never use profile fields as identity keys';
COMMENT ON COLUMN yumpoo.external_identity.raw_profile_hash IS
    'Lowercase SHA-256 of the whitelisted normalized directory profile';
