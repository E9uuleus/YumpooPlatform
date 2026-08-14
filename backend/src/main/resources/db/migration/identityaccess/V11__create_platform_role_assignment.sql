CREATE TABLE yumpoo.platform_role_assignment (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_code varchar(32) NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    granted_by_actor_type varchar(16) NOT NULL,
    granted_by_user_id uuid,
    granted_by_system_code varchar(64),
    grant_reason varchar(500) NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_by_user_id uuid,
    revoked_at timestamptz,
    revoke_reason varchar(500),
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_platform_role_assignment_company
        FOREIGN KEY (company_id) REFERENCES yumpoo.company (id),
    CONSTRAINT fk_platform_role_assignment_user_company
        FOREIGN KEY (user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_platform_role_assignment_grantor_company
        FOREIGN KEY (granted_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_platform_role_assignment_revoker_company
        FOREIGN KEY (revoked_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_platform_role_assignment_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_platform_role_assignment_role_scope CHECK (
        (role_code = 'APP_MANAGER' AND scope_type = 'PLATFORM')
        OR (role_code = 'COMPANY_ADMIN' AND scope_type = 'COMPANY')
    ),
    CONSTRAINT ck_platform_role_assignment_scope_company CHECK (scope_id = company_id),
    CONSTRAINT ck_platform_role_assignment_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_platform_role_assignment_grant_actor CHECK (
        (granted_by_actor_type = 'USER'
            AND granted_by_user_id IS NOT NULL
            AND granted_by_system_code IS NULL)
        OR (granted_by_actor_type = 'SYSTEM'
            AND granted_by_user_id IS NULL
            AND granted_by_system_code ~ '^[A-Z][A-Z0-9_]{1,63}$')
    ),
    CONSTRAINT ck_platform_role_assignment_grant_reason CHECK (
        char_length(grant_reason) BETWEEN 1 AND 500
        AND grant_reason = btrim(grant_reason)
    ),
    CONSTRAINT ck_platform_role_assignment_revocation CHECK (
        (status = 'ACTIVE'
            AND revoked_by_user_id IS NULL
            AND revoked_at IS NULL
            AND revoke_reason IS NULL)
        OR (status = 'REVOKED'
            AND revoked_by_user_id IS NOT NULL
            AND revoked_at IS NOT NULL
            AND revoke_reason IS NOT NULL
            AND char_length(revoke_reason) BETWEEN 1 AND 500
            AND revoke_reason = btrim(revoke_reason)
            AND revoked_at >= granted_at)
    ),
    CONSTRAINT ck_platform_role_assignment_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_platform_role_assignment_timestamps CHECK (
        updated_at >= created_at AND created_at >= granted_at
    )
);

CREATE UNIQUE INDEX uq_platform_role_assignment_active
    ON yumpoo.platform_role_assignment (
        company_id, user_id, role_code, scope_type, scope_id
    )
    WHERE status = 'ACTIVE';

CREATE INDEX idx_platform_role_assignment_user_status
    ON yumpoo.platform_role_assignment (company_id, user_id, status, granted_at DESC);

COMMENT ON COLUMN yumpoo.platform_role_assignment.granted_by_actor_type IS
    'SYSTEM is reserved for controlled bootstrap/break-glass flows; M1-08 exposes no write path.';
