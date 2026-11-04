ALTER TABLE yumpoo.work_item_time_session
    DROP CONSTRAINT ck_work_item_time_session_subject;

ALTER TABLE yumpoo.work_item_time_session
    ADD CONSTRAINT ck_work_item_time_session_subject CHECK (
        (time_category IS NULL AND work_item_id IS NOT NULL AND project_id IS NOT NULL)
        OR (time_category IS NOT NULL AND time_category IN ('BUSINESS_TRIP', 'LEARNING', 'BUSINESS', 'MEETING', 'MIGRATED')
            AND work_item_id IS NULL AND project_id IS NULL));

ALTER TABLE yumpoo.work_item_time_share
    DROP CONSTRAINT ck_work_item_time_share_subject;

ALTER TABLE yumpoo.work_item_time_share
    ADD CONSTRAINT ck_work_item_time_share_subject CHECK (
        (time_category IS NULL AND work_item_id IS NOT NULL AND project_id IS NOT NULL)
        OR (time_category IS NOT NULL AND time_category IN ('BUSINESS_TRIP', 'LEARNING', 'BUSINESS', 'MEETING', 'MIGRATED')
            AND work_item_id IS NULL AND project_id IS NULL));
