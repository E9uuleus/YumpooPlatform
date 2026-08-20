ALTER TABLE yumpoo.governance_issue
    DROP CONSTRAINT uq_governance_issue_detected_event,
    DROP CONSTRAINT uq_governance_issue_resolved_event,
    DROP CONSTRAINT ck_governance_issue_type,
    DROP CONSTRAINT ck_governance_issue_target;

ALTER TABLE yumpoo.governance_issue
    ADD CONSTRAINT uq_governance_issue_detected_target
        UNIQUE (detected_event_id, issue_type, target_type, target_id),
    ADD CONSTRAINT uq_governance_issue_resolved_target
        UNIQUE (resolved_event_id, issue_type, target_type, target_id),
    ADD CONSTRAINT ck_governance_issue_type
        CHECK (issue_type IN ('APP_MANAGER_MISSING', 'OWNER_MISSING')),
    ADD CONSTRAINT ck_governance_issue_target CHECK (
        (issue_type = 'APP_MANAGER_MISSING'
            AND target_type = 'COMPANY'
            AND target_id = company_id)
        OR (issue_type = 'OWNER_MISSING' AND target_type = 'PRODUCT')
    );

COMMENT ON CONSTRAINT uq_governance_issue_detected_target ON yumpoo.governance_issue IS
    'One source event may open independent governance issues for multiple scoped targets.';
