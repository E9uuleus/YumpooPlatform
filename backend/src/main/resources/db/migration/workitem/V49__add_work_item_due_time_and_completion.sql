ALTER TABLE yumpoo.work_item
    ADD COLUMN due_time time without time zone,
    ADD COLUMN completed_at timestamptz,
    ADD CONSTRAINT ck_work_item_due_time CHECK (
        due_time IS NULL OR (
            due_date IS NOT NULL AND due_time < time '24:00'
            AND EXTRACT(SECOND FROM due_time) = 0
        )
    ),
    ADD CONSTRAINT ck_work_item_completed_at CHECK (
        completed_at IS NULL OR (
            status_category = 'DONE'
            AND completed_at >= created_at AND completed_at <= updated_at
        )
    );
