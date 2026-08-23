CREATE INDEX idx_work_item_content_updated_page
    ON yumpoo.work_item (company_id, content_id, updated_at DESC, id ASC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_work_item_content_assignee
    ON yumpoo.work_item (company_id, content_id, assignee_user_id)
    WHERE deleted_at IS NULL AND assignee_user_id IS NOT NULL;

CREATE INDEX idx_work_item_content_due_date
    ON yumpoo.work_item (company_id, content_id, due_date)
    WHERE deleted_at IS NULL AND due_date IS NOT NULL;
