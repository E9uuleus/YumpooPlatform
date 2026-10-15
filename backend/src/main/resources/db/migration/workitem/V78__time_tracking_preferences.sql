ALTER TABLE yumpoo.work_item_timer_state
    ADD COLUMN timer_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN workday_start_minute smallint NOT NULL DEFAULT 540,
    ADD COLUMN workday_end_minute smallint NOT NULL DEFAULT 1080,
    ADD CONSTRAINT ck_work_item_timer_state_workday
        CHECK (workday_start_minute >= 0 AND workday_end_minute <= 1440 AND workday_end_minute > workday_start_minute);
