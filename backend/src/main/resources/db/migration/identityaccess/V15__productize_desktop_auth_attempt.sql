ALTER TABLE yumpoo.desktop_auth_attempt
    ADD COLUMN authorization_claimed_at timestamptz NULL,
    ADD COLUMN authenticated_user_id uuid NULL,
    ADD COLUMN client_version varchar(32) NULL,
    ADD COLUMN client_protocol_version varchar(16) NULL,
    ADD CONSTRAINT fk_desktop_auth_attempt_authenticated_user
        FOREIGN KEY (authenticated_user_id) REFERENCES yumpoo.identity_user (id);

ALTER TABLE yumpoo.desktop_auth_attempt
    DROP CONSTRAINT ck_desktop_auth_attempt_oauth_state_hash,
    DROP CONSTRAINT ck_desktop_auth_attempt_handoff_lifecycle;

ALTER TABLE yumpoo.desktop_auth_attempt
    ADD CONSTRAINT ck_desktop_auth_attempt_oauth_state_hash CHECK (
        oauth_state_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_desktop_auth_attempt_client_binding CHECK (
        (client_version IS NULL AND client_protocol_version IS NULL)
        OR
        (client_version IS NOT NULL AND client_protocol_version IS NOT NULL)
    ),
    ADD CONSTRAINT ck_desktop_auth_attempt_authorization_claim CHECK (
        authorization_claimed_at IS NULL
        OR (
            client_version IS NOT NULL
            AND authorization_claimed_at >= created_at
            AND authorization_claimed_at < authorize_expires_at
        )
    ),
    ADD CONSTRAINT ck_desktop_auth_attempt_handoff_lifecycle CHECK (
        (
            handoff_code_hash IS NULL
            AND corp_fingerprint IS NULL
            AND member_fingerprint IS NULL
            AND authenticated_user_id IS NULL
            AND handoff_issued_at IS NULL
            AND handoff_expires_at IS NULL
            AND consumed_at IS NULL
        )
        OR
        (
            handoff_code_hash IS NOT NULL
            AND handoff_issued_at IS NOT NULL
            AND handoff_expires_at IS NOT NULL
            AND (
                (
                    corp_fingerprint IS NOT NULL
                    AND member_fingerprint IS NOT NULL
                    AND authenticated_user_id IS NULL
                )
                OR
                (
                    corp_fingerprint IS NULL
                    AND member_fingerprint IS NULL
                    AND authenticated_user_id IS NOT NULL
                    AND client_version IS NOT NULL
                    AND authorization_claimed_at IS NOT NULL
                )
            )
        )
    );

CREATE INDEX idx_desktop_auth_attempt_authenticated_user
    ON yumpoo.desktop_auth_attempt (authenticated_user_id)
    WHERE authenticated_user_id IS NOT NULL;

COMMENT ON COLUMN yumpoo.desktop_auth_attempt.authorization_claimed_at IS
    'Single-use claim made before exchanging a product Electron OAuth code';

COMMENT ON COLUMN yumpoo.desktop_auth_attempt.authenticated_user_id IS
    'Formal Yumpoo user bound to a product Electron handoff; null for M0 diagnostics';
