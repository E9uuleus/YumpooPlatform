CREATE TABLE yumpoo.wecom_oauth_attempt (
    state_hash char(64) PRIMARY KEY,
    nonce_hash char(64) NOT NULL,
    request_id varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    CONSTRAINT uq_wecom_oauth_attempt_nonce_hash UNIQUE (nonce_hash),
    CONSTRAINT ck_wecom_oauth_attempt_state_hash CHECK (
        state_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_wecom_oauth_attempt_nonce_hash CHECK (
        nonce_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_wecom_oauth_attempt_request_id CHECK (
        request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
    ),
    CONSTRAINT ck_wecom_oauth_attempt_expires_at CHECK (
        expires_at > created_at
    ),
    CONSTRAINT ck_wecom_oauth_attempt_consumed_at CHECK (
        consumed_at IS NULL
        OR (consumed_at >= created_at AND consumed_at < expires_at)
    )
);

CREATE INDEX idx_wecom_oauth_attempt_expires_at
    ON yumpoo.wecom_oauth_attempt (expires_at);

COMMENT ON TABLE yumpoo.wecom_oauth_attempt IS
    'Hashed single-use state and browser nonce for WeCom OAuth verification';

COMMENT ON COLUMN yumpoo.wecom_oauth_attempt.expires_at IS
    'Cleanup metadata; M0-12 does not perform automatic deletion';
