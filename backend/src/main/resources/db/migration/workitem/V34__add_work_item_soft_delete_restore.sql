ALTER TABLE yumpoo.work_item
    DROP CONSTRAINT uq_work_item_active_lane_rank,
    ADD COLUMN active_lane_rank varchar(39)
        GENERATED ALWAYS AS (
            CASE WHEN deleted_at IS NULL THEN rank ELSE NULL END
        ) STORED,
    ADD CONSTRAINT uq_work_item_active_lane_rank
        UNIQUE (content_id, status_code, active_lane_rank)
        DEFERRABLE INITIALLY DEFERRED;

COMMENT ON COLUMN yumpoo.work_item.active_lane_rank IS
    'Generated active-only rank key; tombstones retain historical rank without occupying a lane position.';
