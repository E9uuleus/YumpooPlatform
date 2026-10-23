ALTER TABLE yumpoo.work_item_time_session
    ADD COLUMN time_category varchar(32),
    ALTER COLUMN project_id DROP NOT NULL,
    ALTER COLUMN work_item_id DROP NOT NULL;

ALTER TABLE yumpoo.work_item_time_session
    ADD CONSTRAINT ck_work_item_time_session_subject CHECK (
        (time_category IS NULL AND work_item_id IS NOT NULL AND project_id IS NOT NULL)
        OR (time_category IS NOT NULL AND time_category IN ('BUSINESS_TRIP', 'LEARNING', 'BUSINESS', 'MEETING')
            AND work_item_id IS NULL AND project_id IS NULL));

CREATE UNIQUE INDEX uq_work_item_running_timer_category
    ON yumpoo.work_item_time_session (company_id, user_id, time_category)
    WHERE time_category IS NOT NULL AND stopped_at IS NULL AND deleted_at IS NULL;

ALTER TABLE yumpoo.work_item_time_share
    ADD COLUMN time_category varchar(32),
    ALTER COLUMN project_id DROP NOT NULL,
    ALTER COLUMN work_item_id DROP NOT NULL;

ALTER TABLE yumpoo.work_item_time_share
    ADD CONSTRAINT ck_work_item_time_share_subject CHECK (
        (time_category IS NULL AND work_item_id IS NOT NULL AND project_id IS NOT NULL)
        OR (time_category IS NOT NULL AND time_category IN ('BUSINESS_TRIP', 'LEARNING', 'BUSINESS', 'MEETING')
            AND work_item_id IS NULL AND project_id IS NULL));

CREATE INDEX ix_work_item_time_share_category
    ON yumpoo.work_item_time_share (company_id, started_at)
    WHERE time_category IS NOT NULL;
