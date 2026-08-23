CREATE TABLE yumpoo.work_item_rank_lane (
    content_id uuid NOT NULL,
    status_code varchar(32) NOT NULL,
    CONSTRAINT pk_work_item_rank_lane PRIMARY KEY (content_id, status_code),
    CONSTRAINT fk_work_item_rank_lane_content
        FOREIGN KEY (content_id) REFERENCES yumpoo.content (id) ON DELETE CASCADE,
    CONSTRAINT ck_work_item_rank_lane_status
        CHECK (status_code ~ '^[A-Z][A-Z0-9_]{1,31}$')
);

INSERT INTO yumpoo.work_item_rank_lane (content_id, status_code)
SELECT DISTINCT content_id, status_code
  FROM yumpoo.work_item
ON CONFLICT (content_id, status_code) DO NOTHING;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY content_id, status_code
               ORDER BY item_sequence DESC, id ASC
           ) AS position,
           count(*) OVER (PARTITION BY content_id, status_code) AS lane_size
      FROM yumpoo.work_item
     WHERE deleted_at IS NULL
), assigned AS (
    SELECT id,
           lpad(floor(
               repeat('9', 39)::numeric * position / (lane_size + 1)
           )::text, 39, '0') AS rank
      FROM ranked
)
UPDATE yumpoo.work_item item
   SET rank = assigned.rank
  FROM assigned
 WHERE item.id = assigned.id;

ALTER TABLE yumpoo.work_item
    DROP CONSTRAINT ck_work_item_rank,
    ALTER COLUMN rank SET NOT NULL,
    ADD CONSTRAINT ck_work_item_rank CHECK (
        rank ~ '^[0-9]{39}$'
        AND rank <> repeat('0', 39)
        AND rank <> repeat('9', 39)
    ),
    ADD CONSTRAINT uq_work_item_active_lane_rank
        UNIQUE NULLS NOT DISTINCT (content_id, status_code, rank, deleted_at)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_work_item_content_status_rank_page
    ON yumpoo.work_item (company_id, content_id, status_code, rank ASC, id ASC)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE yumpoo.work_item_rank_lane IS
    'Transaction lock rows that serialize Work Item ordering inside a Content status lane.';
