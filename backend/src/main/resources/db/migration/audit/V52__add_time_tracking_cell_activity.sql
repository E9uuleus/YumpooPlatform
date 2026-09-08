ALTER TABLE yumpoo.work_item_cell_activity
    DROP CONSTRAINT ck_work_item_cell_activity_column;
ALTER TABLE yumpoo.work_item_cell_activity
    ADD CONSTRAINT ck_work_item_cell_activity_column CHECK (
        column_code IN ('WORK_ITEM_NAME', 'ASSIGNEE', 'STATUS', 'PRIORITY', 'DUE_DATE', 'CONTENT', 'TIME_TRACKING')
    );
