ALTER TABLE yumpoo.work_item_table_settings
    ADD COLUMN grouping_settings jsonb,
    ADD COLUMN completed_status_codes jsonb;

ALTER TABLE yumpoo.work_item_timer_state
    ADD COLUMN hide_completed boolean NOT NULL DEFAULT false;
