ALTER TABLE yumpoo.work_item_update
    DROP CONSTRAINT ck_work_item_update_edit_deadline,
    DROP CONSTRAINT ck_work_item_update_edit_facts,
    DROP CONSTRAINT ck_work_item_update_delete_facts,
    DROP COLUMN edit_deadline_at,
    ADD COLUMN parent_update_id uuid,
    ADD COLUMN pinned_at timestamptz,
    ADD COLUMN pinned_by_user_id uuid,
    ADD CONSTRAINT uq_update_thread_scope UNIQUE (id, company_id, work_item_id),
    ADD CONSTRAINT fk_update_thread_parent FOREIGN KEY (parent_update_id, company_id, work_item_id)
        REFERENCES yumpoo.work_item_update (id, company_id, work_item_id),
    ADD CONSTRAINT fk_update_pinner FOREIGN KEY (pinned_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    ADD CONSTRAINT ck_update_parent CHECK (parent_update_id IS NULL OR parent_update_id <> id),
    ADD CONSTRAINT ck_update_pin CHECK (
        (pinned_at IS NULL AND pinned_by_user_id IS NULL) OR
        (pinned_at IS NOT NULL AND pinned_by_user_id IS NOT NULL AND parent_update_id IS NULL
            AND status <> 'DELETED' AND pinned_at >= created_at)),
    ADD CONSTRAINT ck_work_item_update_edit_facts CHECK (
        (status = 'PUBLISHED' AND edited_at IS NULL AND edited_by_user_id IS NULL)
        OR (status IN ('EDITED', 'DELETED') AND edited_at IS NOT NULL
            AND edited_by_user_id = author_user_id AND edited_at >= created_at)
        OR (status = 'DELETED' AND edited_at IS NULL AND edited_by_user_id IS NULL)),
    ADD CONSTRAINT ck_work_item_update_delete_facts CHECK (
        (status <> 'DELETED' AND deleted_at IS NULL AND deleted_by_user_id IS NULL AND delete_reason IS NULL)
        OR (status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL AND deleted_at >= created_at));

CREATE INDEX idx_update_thread_page ON yumpoo.work_item_update (company_id, parent_update_id, created_at, id)
    WHERE status <> 'DELETED';
CREATE INDEX idx_update_root_page ON yumpoo.work_item_update (company_id, work_item_id, created_at, id)
    WHERE parent_update_id IS NULL AND status <> 'DELETED';
CREATE INDEX idx_update_pinned ON yumpoo.work_item_update (company_id, work_item_id, pinned_at DESC, id DESC)
    WHERE parent_update_id IS NULL AND pinned_at IS NOT NULL AND status <> 'DELETED';

COMMENT ON TABLE yumpoo.work_item_update IS 'Two-level work item discussion; author edits have no time limit; deleted bodies are cleared.';
