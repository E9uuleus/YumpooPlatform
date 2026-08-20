ALTER TABLE yumpoo.project_membership
    DROP CONSTRAINT ck_project_membership_remove_facts;

ALTER TABLE yumpoo.project_membership
    ADD CONSTRAINT ck_project_membership_remove_facts CHECK (
        (status = 'ACTIVE'
            AND removed_at IS NULL
            AND removed_by_user_id IS NULL
            AND remove_reason IS NULL)
        OR (status = 'REMOVED'
            AND removed_at IS NOT NULL
            AND removed_by_user_id IS NOT NULL
            AND (remove_reason IS NULL OR (
                char_length(remove_reason) BETWEEN 1 AND 500
                AND remove_reason = btrim(remove_reason)
            ))
            AND removed_at >= joined_at)
    );
