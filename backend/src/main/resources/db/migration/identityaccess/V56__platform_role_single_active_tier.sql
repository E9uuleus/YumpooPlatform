ALTER TABLE yumpoo.platform_role_assignment
    ADD COLUMN revoked_by_actor_type varchar(16),
    ADD COLUMN revoked_by_system_code varchar(64),
    DROP CONSTRAINT ck_platform_role_assignment_revocation;

UPDATE yumpoo.platform_role_assignment
SET revoked_by_actor_type = 'USER'
WHERE status = 'REVOKED';

UPDATE yumpoo.platform_role_assignment subordinate
SET status = 'REVOKED', revoked_by_actor_type = 'SYSTEM',
    revoked_by_system_code = 'ROLE_TIER_CONSOLIDATION',
    revoked_at = transaction_timestamp(),
    revoke_reason = 'Consolidated into APP_MANAGER tier',
    row_version = row_version + 1, updated_at = transaction_timestamp()
WHERE subordinate.status = 'ACTIVE' AND subordinate.role_code = 'COMPANY_ADMIN'
  AND EXISTS (
      SELECT 1 FROM yumpoo.platform_role_assignment manager
      WHERE manager.company_id = subordinate.company_id
        AND manager.user_id = subordinate.user_id
        AND manager.status = 'ACTIVE' AND manager.role_code = 'APP_MANAGER'
  );

ALTER TABLE yumpoo.platform_role_assignment
    ADD CONSTRAINT ck_platform_role_assignment_revocation CHECK (
        (status = 'ACTIVE'
            AND revoked_by_actor_type IS NULL AND revoked_by_user_id IS NULL
            AND revoked_by_system_code IS NULL AND revoked_at IS NULL AND revoke_reason IS NULL)
        OR (status = 'REVOKED'
            AND revoked_by_actor_type IS NOT NULL
            AND ((revoked_by_actor_type = 'USER' AND revoked_by_user_id IS NOT NULL
                    AND revoked_by_system_code IS NULL)
                OR (revoked_by_actor_type = 'SYSTEM' AND revoked_by_user_id IS NULL
                    AND revoked_by_system_code IS NOT NULL
                    AND revoked_by_system_code ~ '^[A-Z][A-Z0-9_]{1,63}$'))
            AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL
            AND char_length(revoke_reason) BETWEEN 1 AND 500
            AND revoke_reason = btrim(revoke_reason) AND revoked_at >= granted_at)
    );

DROP INDEX yumpoo.uq_platform_role_assignment_active;
CREATE UNIQUE INDEX uq_platform_role_assignment_active
    ON yumpoo.platform_role_assignment (company_id, user_id)
    WHERE status = 'ACTIVE';
