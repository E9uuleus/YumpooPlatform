ALTER TABLE yumpoo.admin_override
    DROP CONSTRAINT ck_admin_override_action;

ALTER TABLE yumpoo.admin_override
    ADD CONSTRAINT ck_admin_override_action CHECK (action IN (
        'PROJECT_ARCHIVE_WITH_OPEN_ITEMS',
        'PRODUCT_ARCHIVE_WITH_BLOCKERS',
        'WORKSPACE_ARCHIVE_WITH_ACTIVE_PROJECTS'
    ));

ALTER TABLE yumpoo.admin_override
    DROP CONSTRAINT ck_admin_override_target_type;

ALTER TABLE yumpoo.admin_override
    ADD CONSTRAINT ck_admin_override_target_type
        CHECK (target_type IN ('PROJECT', 'PRODUCT', 'WORKSPACE'));
