ALTER TABLE yumpoo.work_item_time_session ADD COLUMN category_project_id uuid;

ALTER TABLE yumpoo.work_item_time_share ADD COLUMN category_project_id uuid;

CREATE INDEX ix_work_item_time_share_category_project
    ON yumpoo.work_item_time_share (company_id, category_project_id, started_at)
    WHERE category_project_id IS NOT NULL;
