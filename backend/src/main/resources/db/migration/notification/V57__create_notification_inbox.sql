CREATE TABLE yumpoo.notification_projection_state (
    projection_code varchar(32) PRIMARY KEY CHECK (projection_code = 'INBOX_V1'),
    accepted_from timestamptz NOT NULL
);
INSERT INTO yumpoo.notification_projection_state VALUES ('INBOX_V1', clock_timestamp());

CREATE TABLE yumpoo.notification_event (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES yumpoo.company(id),
    source_event_id uuid NOT NULL UNIQUE,
    event_type varchar(160) NOT NULL,
    payload_schema_version integer NOT NULL,
    target_kind varchar(32) NOT NULL,
    project_id uuid NOT NULL,
    work_item_id uuid,
    update_id uuid,
    subject_user_id uuid,
    actor_user_id uuid,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_notification_event_company UNIQUE (company_id, id),
    CONSTRAINT ck_notification_event_target CHECK (
        (target_kind = 'PROJECT' AND work_item_id IS NULL AND update_id IS NULL)
        OR (target_kind = 'WORK_ITEM' AND work_item_id IS NOT NULL AND update_id IS NULL)
        OR (target_kind = 'WORK_ITEM_UPDATE' AND work_item_id IS NOT NULL AND update_id IS NOT NULL))
);
CREATE TABLE yumpoo.user_notification (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    notification_event_id uuid NOT NULL,
    recipient_user_id uuid NOT NULL,
    reason varchar(32) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'UNREAD',
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    read_at timestamptz,
    archived_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_user_notification_event FOREIGN KEY (company_id, notification_event_id)
        REFERENCES yumpoo.notification_event(company_id,id) ON DELETE CASCADE,
    CONSTRAINT uq_user_notification_recipient UNIQUE (notification_event_id, recipient_user_id),
    CONSTRAINT ck_user_notification_reason CHECK (reason IN ('MENTION','REPLY','COMMENT','ASSIGNED',
        'PROJECT_MEMBER_ADDED','PROJECT_MEMBER_REMOVED','PROJECT_OWNER_ASSIGNED','PROJECT_OWNER_TRANSFERRED')),
    CONSTRAINT ck_user_notification_state CHECK (
        (state = 'UNREAD' AND read_at IS NULL AND archived_at IS NULL)
        OR (state = 'READ' AND read_at IS NOT NULL AND archived_at IS NULL)
        OR (state = 'ARCHIVED' AND archived_at IS NOT NULL))
);
CREATE INDEX idx_user_notification_active ON yumpoo.user_notification
    (company_id, recipient_user_id, created_at DESC, id DESC) WHERE state <> 'ARCHIVED';
CREATE INDEX idx_user_notification_archived ON yumpoo.user_notification
    (company_id, recipient_user_id, created_at DESC, id DESC) WHERE state = 'ARCHIVED';
CREATE INDEX idx_user_notification_unread ON yumpoo.user_notification
    (company_id, recipient_user_id, reason) WHERE state = 'UNREAD';
