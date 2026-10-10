ALTER TABLE yumpoo.user_notification DROP CONSTRAINT ck_user_notification_reason;
ALTER TABLE yumpoo.user_notification ADD CONSTRAINT ck_user_notification_reason CHECK (reason IN (
    'MENTION', 'REPLY', 'COMMENT', 'ASSIGNED', 'PROJECT_MEMBER_ADDED', 'PROJECT_MEMBER_REMOVED',
    'PROJECT_OWNER_ASSIGNED', 'PROJECT_OWNER_TRANSFERRED', 'CONNECTION_CREATED',
    'PROJECT_DELETION_SCHEDULED', 'PROJECT_DELETION_REMINDER', 'PROJECT_DELETION_CANCELLED'));

ALTER TABLE yumpoo.notification_projection_state DROP CONSTRAINT notification_projection_state_projection_code_check;
ALTER TABLE yumpoo.notification_projection_state ADD CONSTRAINT notification_projection_state_projection_code_check
    CHECK (projection_code IN ('INBOX_V1', 'CONNECTION_CREATED_V1', 'PROJECT_DELETION_V1'));
INSERT INTO yumpoo.notification_projection_state (projection_code, accepted_from)
    VALUES ('PROJECT_DELETION_V1', clock_timestamp());

ALTER TABLE yumpoo.notification_event ADD COLUMN deletion_purge_after timestamptz;
