DROP INDEX yumpoo.idx_work_item_cell_activity_facets;

CREATE INDEX idx_work_item_cell_activity_facets
    ON yumpoo.work_item_cell_activity (
        company_id, work_item_id, actor_user_id, column_code, occurred_at DESC
    );
