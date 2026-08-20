ALTER TABLE yumpoo.governance_issue
    DROP CONSTRAINT ck_governance_issue_target;

ALTER TABLE yumpoo.governance_issue
    ADD CONSTRAINT ck_governance_issue_target CHECK (
        (issue_type = 'APP_MANAGER_MISSING'
            AND target_type = 'COMPANY'
            AND target_id = company_id)
        OR (issue_type = 'OWNER_MISSING' AND target_type IN ('PRODUCT', 'PROJECT'))
    );
