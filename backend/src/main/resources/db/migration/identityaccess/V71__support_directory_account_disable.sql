ALTER TABLE yumpoo.directory_sync_staging_member
    ADD COLUMN account_disabled boolean NOT NULL DEFAULT false;

ALTER TABLE yumpoo.identity_user
    DROP CONSTRAINT ck_identity_user_disabled_facts,
    ADD CONSTRAINT ck_identity_user_disabled_facts CHECK (
        (
            account_disabled_at IS NULL
            AND account_disabled_by_user_id IS NULL
            AND account_disabled_reason IS NULL
            AND account_status = 'ENABLED'
        ) OR (
            account_disabled_at IS NOT NULL
            AND account_disabled_reason IS NOT NULL
            AND account_disabled_reason = btrim(account_disabled_reason)
            AND length(account_disabled_reason) BETWEEN 1 AND 500
            AND (
                account_disabled_by_user_id IS NOT NULL
                OR account_disabled_reason IN ('DIRECTORY_ACCOUNT_DISABLED', 'DIRECTORY_SNAPSHOT_MISSING')
            )
        )
    );
