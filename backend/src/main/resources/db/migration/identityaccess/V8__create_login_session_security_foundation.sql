ALTER TABLE yumpoo.identity_user
    ADD COLUMN authorization_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_identity_user_authorization_version
        CHECK (authorization_version >= 0);

CREATE TABLE yumpoo.login_session (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status varchar(20) NOT NULL,
    session_token_fingerprint char(64) NOT NULL,
    session_key_version varchar(32) NOT NULL,
    csrf_token_fingerprint char(64) NULL,
    csrf_key_version varchar(32) NULL,
    issued_authorization_version bigint NOT NULL,
    client_type varchar(20) NOT NULL,
    client_version varchar(64) NULL,
    issued_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    idle_expires_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL,
    revoke_reason varchar(40) NULL,
    purge_after timestamptz NOT NULL,
    CONSTRAINT fk_login_session_user_company FOREIGN KEY (user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT uq_login_session_token_fingerprint UNIQUE (
        session_key_version,
        session_token_fingerprint
    ),
    CONSTRAINT ck_login_session_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_login_session_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_login_session_token_fingerprint CHECK (
        session_token_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_login_session_session_key_version CHECK (
        session_key_version = btrim(session_key_version)
        AND length(session_key_version) BETWEEN 1 AND 32
        AND session_key_version ~ '^[A-Za-z0-9._-]+$'
    ),
    CONSTRAINT ck_login_session_csrf_fingerprint CHECK (
        (csrf_token_fingerprint IS NULL AND csrf_key_version IS NULL)
        OR (
            csrf_token_fingerprint ~ '^[0-9a-f]{64}$'
            AND csrf_key_version = btrim(csrf_key_version)
            AND length(csrf_key_version) BETWEEN 1 AND 32
            AND csrf_key_version ~ '^[A-Za-z0-9._-]+$'
        )
    ),
    CONSTRAINT ck_login_session_authorization_version CHECK (
        issued_authorization_version >= 0
    ),
    CONSTRAINT ck_login_session_client CHECK (
        client_type IN ('WEB', 'ELECTRON')
        AND (
            client_version IS NULL
            OR (
                client_version = btrim(client_version)
                AND length(client_version) BETWEEN 1 AND 64
            )
        )
    ),
    CONSTRAINT ck_login_session_lifecycle CHECK (
        issued_at <= last_seen_at
        AND last_seen_at < idle_expires_at
        AND idle_expires_at <= absolute_expires_at
        AND purge_after = absolute_expires_at + interval '24 hours'
    ),
    CONSTRAINT ck_login_session_revocation CHECK (
        (
            status = 'ACTIVE'
            AND revoked_at IS NULL
            AND revoke_reason IS NULL
        ) OR (
            status IN ('REVOKED', 'EXPIRED')
            AND revoked_at IS NOT NULL
            AND revoked_at >= issued_at
            AND revoke_reason IS NOT NULL
            AND revoke_reason IN (
                'ROTATED',
                'USER_LOGOUT',
                'EMPLOYMENT_LEFT',
                'ACCOUNT_DISABLED',
                'AUTHORIZATION_CHANGED',
                'ADMIN_FORCED',
                'IDLE_EXPIRED',
                'ABSOLUTE_EXPIRED'
            )
        )
    )
);

CREATE INDEX idx_login_session_user_active
    ON yumpoo.login_session (user_id, company_id, issued_at, id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_login_session_purge_after
    ON yumpoo.login_session (purge_after, id);

COMMENT ON TABLE yumpoo.login_session IS
    'Opaque server-side browser sessions owned by identityaccess';
COMMENT ON COLUMN yumpoo.login_session.session_token_fingerprint IS
    'Purpose-separated HMAC-SHA-256 fingerprint; raw session credentials are never stored';
COMMENT ON COLUMN yumpoo.login_session.csrf_token_fingerprint IS
    'Purpose-separated HMAC-SHA-256 fingerprint bound to this session';
