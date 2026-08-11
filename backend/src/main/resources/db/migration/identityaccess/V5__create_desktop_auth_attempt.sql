CREATE TABLE yumpoo.desktop_auth_attempt (
    desktop_state_hash char(64) PRIMARY KEY,
    oauth_state_hash char(64) NOT NULL,
    pkce_s256_challenge char(43) NOT NULL,
    request_id varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    authorize_expires_at timestamptz NOT NULL,
    handoff_code_hash char(64) NULL,
    corp_fingerprint char(64) NULL,
    member_fingerprint char(64) NULL,
    handoff_issued_at timestamptz NULL,
    handoff_expires_at timestamptz NULL,
    consumed_at timestamptz NULL,
    CONSTRAINT uq_desktop_auth_attempt_oauth_state_hash UNIQUE (oauth_state_hash),
    CONSTRAINT uq_desktop_auth_attempt_handoff_code_hash UNIQUE (handoff_code_hash),
    CONSTRAINT ck_desktop_auth_attempt_desktop_state_hash CHECK (
        desktop_state_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_desktop_auth_attempt_oauth_state_hash CHECK (
        oauth_state_hash ~ '^[0-9a-f]{64}$'
        AND oauth_state_hash <> desktop_state_hash
    ),
    CONSTRAINT ck_desktop_auth_attempt_pkce_s256_challenge CHECK (
        pkce_s256_challenge ~ '^[A-Za-z0-9_-]{43}$'
    ),
    CONSTRAINT ck_desktop_auth_attempt_request_id CHECK (
        request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    CONSTRAINT ck_desktop_auth_attempt_authorize_window CHECK (
        authorize_expires_at = created_at + interval '5 minutes'
    ),
    CONSTRAINT ck_desktop_auth_attempt_handoff_code_hash CHECK (
        handoff_code_hash IS NULL
        OR handoff_code_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_desktop_auth_attempt_corp_fingerprint CHECK (
        corp_fingerprint IS NULL
        OR corp_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_desktop_auth_attempt_member_fingerprint CHECK (
        member_fingerprint IS NULL
        OR member_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_desktop_auth_attempt_handoff_lifecycle CHECK (
        (
            handoff_code_hash IS NULL
            AND corp_fingerprint IS NULL
            AND member_fingerprint IS NULL
            AND handoff_issued_at IS NULL
            AND handoff_expires_at IS NULL
            AND consumed_at IS NULL
        )
        OR
        (
            handoff_code_hash IS NOT NULL
            AND corp_fingerprint IS NOT NULL
            AND member_fingerprint IS NOT NULL
            AND handoff_issued_at IS NOT NULL
            AND handoff_expires_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_desktop_auth_attempt_handoff_window CHECK (
        handoff_issued_at IS NULL
        OR (
            handoff_issued_at >= created_at
            AND handoff_issued_at < authorize_expires_at
            AND handoff_expires_at = handoff_issued_at + interval '60 seconds'
        )
    ),
    CONSTRAINT ck_desktop_auth_attempt_consumed_at CHECK (
        consumed_at IS NULL
        OR (
            handoff_issued_at IS NOT NULL
            AND consumed_at >= handoff_issued_at
            AND consumed_at < handoff_expires_at
        )
    )
);

CREATE INDEX idx_desktop_auth_attempt_authorize_expires_at
    ON yumpoo.desktop_auth_attempt (authorize_expires_at);

CREATE INDEX idx_desktop_auth_attempt_handoff_expires_at
    ON yumpoo.desktop_auth_attempt (handoff_expires_at)
    WHERE handoff_expires_at IS NOT NULL AND consumed_at IS NULL;

COMMENT ON TABLE yumpoo.desktop_auth_attempt IS
    'Hashed single-use desktop OAuth handoff with PKCE S256 proof';

COMMENT ON COLUMN yumpoo.desktop_auth_attempt.handoff_code_hash IS
    'Only the SHA-256 hash of the 256-bit handoff code is persisted';

COMMENT ON COLUMN yumpoo.desktop_auth_attempt.corp_fingerprint IS
    'HMAC-SHA256 fingerprint; the raw WeCom corporation ID is never persisted';

COMMENT ON COLUMN yumpoo.desktop_auth_attempt.member_fingerprint IS
    'HMAC-SHA256 fingerprint; the raw WeCom member ID is never persisted';
