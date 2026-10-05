ALTER TABLE yumpoo.work_item_cell_activity ADD COLUMN value_key varchar(64) NOT NULL DEFAULT '';
ALTER TABLE yumpoo.work_item_cell_activity DROP CONSTRAINT uq_work_item_cell_activity_projection;
ALTER TABLE yumpoo.work_item_cell_activity ADD CONSTRAINT uq_work_item_cell_activity_projection
    UNIQUE (event_id, projection_code, column_code, value_key);
