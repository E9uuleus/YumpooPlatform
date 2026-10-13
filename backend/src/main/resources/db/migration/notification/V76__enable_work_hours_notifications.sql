ALTER TABLE yumpoo.user_notification DROP CONSTRAINT ck_user_notification_reason;
ALTER TABLE yumpoo.user_notification ADD CONSTRAINT ck_user_notification_reason CHECK (reason IN (
    'MENTION', 'REPLY', 'COMMENT', 'ASSIGNED', 'PROJECT_MEMBER_ADDED', 'PROJECT_MEMBER_REMOVED',
    'PROJECT_OWNER_ASSIGNED', 'PROJECT_OWNER_TRANSFERRED', 'CONNECTION_CREATED',
    'PROJECT_DELETION_SCHEDULED', 'PROJECT_DELETION_REMINDER', 'PROJECT_DELETION_CANCELLED', 'WORK_HOURS_ANOMALY'));

ALTER TABLE yumpoo.notification_event ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE yumpoo.notification_event ADD COLUMN work_date date, ADD COLUMN duration_ms bigint, ADD COLUMN threshold_minutes integer;
ALTER TABLE yumpoo.notification_event DROP CONSTRAINT ck_notification_event_target;
ALTER TABLE yumpoo.notification_event ADD CONSTRAINT ck_notification_event_target CHECK (
    (target_kind = 'PROJECT' AND project_id IS NOT NULL AND work_item_id IS NULL AND update_id IS NULL)
    OR (target_kind = 'WORK_ITEM' AND project_id IS NOT NULL AND work_item_id IS NOT NULL AND update_id IS NULL)
    OR (target_kind = 'WORK_ITEM_UPDATE' AND project_id IS NOT NULL AND work_item_id IS NOT NULL AND update_id IS NOT NULL)
    OR (target_kind = 'MEMBER_DAY' AND project_id IS NULL AND work_item_id IS NULL AND update_id IS NULL
        AND subject_user_id IS NOT NULL AND work_date IS NOT NULL AND duration_ms IS NOT NULL AND threshold_minutes IS NOT NULL));
