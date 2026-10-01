LOCK TABLE yumpoo.attachment IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM yumpoo.attachment
        WHERE owner_type IN ('PRODUCT_FEEDBACK', 'FEEDBACK_UPDATE')
    ) THEN
        RAISE EXCEPTION 'Cannot remove product concept: feedback attachments require explicit data resolution'
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

DELETE FROM yumpoo.activity_event
WHERE scope_type IN ('PRODUCT', 'FEEDBACK')
   OR entity_type = 'PRODUCT'
   OR event_type LIKE 'catalog.product\_%' ESCAPE '\'
   OR event_type = 'catalog.project_product_link_updated';

ALTER TABLE yumpoo.activity_event
    DROP CONSTRAINT ck_activity_event_scope_type,
    ADD CONSTRAINT ck_activity_event_scope_type CHECK (scope_type IN ('PROJECT'));

DELETE FROM yumpoo.outbox_consumer_receipt
WHERE event_id IN (
    SELECT event_id FROM yumpoo.outbox_event
    WHERE event_type IN (
        'catalog.product_created', 'catalog.product_updated',
        'catalog.product_archived', 'catalog.product_restored',
        'catalog.product_owner_reassigned', 'catalog.product_linked_to_project',
        'catalog.project_product_link_updated', 'catalog.product_unlinked_from_project'
    )
);

DELETE FROM yumpoo.outbox_event
WHERE event_type IN (
    'catalog.product_created', 'catalog.product_updated',
    'catalog.product_archived', 'catalog.product_restored',
    'catalog.product_owner_reassigned', 'catalog.product_linked_to_project',
    'catalog.project_product_link_updated', 'catalog.product_unlinked_from_project'
);

DELETE FROM yumpoo.governance_issue WHERE target_type = 'PRODUCT';

ALTER TABLE yumpoo.governance_issue
    DROP CONSTRAINT ck_governance_issue_target,
    ADD CONSTRAINT ck_governance_issue_target CHECK (
        (issue_type = 'APP_MANAGER_MISSING' AND target_type = 'COMPANY' AND target_id = company_id)
        OR (issue_type = 'OWNER_MISSING' AND target_type = 'PROJECT')
    );

DELETE FROM yumpoo.admin_override
WHERE action = 'PRODUCT_ARCHIVE_WITH_BLOCKERS' OR target_type = 'PRODUCT';

ALTER TABLE yumpoo.admin_override
    DROP CONSTRAINT ck_admin_override_action,
    ADD CONSTRAINT ck_admin_override_action CHECK (action IN (
        'PROJECT_ARCHIVE_WITH_OPEN_ITEMS', 'WORKSPACE_ARCHIVE_WITH_ACTIVE_PROJECTS'
    )),
    DROP CONSTRAINT ck_admin_override_target_type,
    ADD CONSTRAINT ck_admin_override_target_type CHECK (target_type IN ('PROJECT', 'WORKSPACE'));

ALTER TABLE yumpoo.attachment
    DROP CONSTRAINT ck_attachment_owner_type,
    ADD CONSTRAINT ck_attachment_owner_type CHECK (owner_type IN ('WORK_ITEM', 'WORK_ITEM_UPDATE'));

DROP TABLE yumpoo.project_product_link;
DROP TABLE yumpoo.product;
