CREATE TABLE yumpoo.work_item_project_order (
    project_id uuid NOT NULL,
    company_id uuid NOT NULL,
    CONSTRAINT pk_work_item_project_order PRIMARY KEY (project_id),
    CONSTRAINT fk_work_item_project_order_project
        FOREIGN KEY (project_id, company_id) REFERENCES yumpoo.project (id, company_id)
        ON DELETE CASCADE
);

INSERT INTO yumpoo.work_item_project_order (project_id, company_id)
SELECT DISTINCT project_id, company_id
  FROM yumpoo.work_item
ON CONFLICT (project_id) DO NOTHING;

ALTER TABLE yumpoo.work_item
    ADD COLUMN project_sort_key varchar(39);

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY project_id
               ORDER BY item_sequence DESC, id ASC
           ) AS position,
           count(*) OVER (PARTITION BY project_id) AS project_size
      FROM yumpoo.work_item
), assigned AS (
    SELECT id,
           lpad(floor(
               repeat('9', 39)::numeric * position / (project_size + 1)
           )::text, 39, '0') AS project_sort_key
      FROM ranked
)
UPDATE yumpoo.work_item item
   SET project_sort_key = assigned.project_sort_key
  FROM assigned
 WHERE item.id = assigned.id;

ALTER TABLE yumpoo.work_item
    ALTER COLUMN project_sort_key SET NOT NULL,
    ADD COLUMN active_project_sort_key varchar(39)
        GENERATED ALWAYS AS (
            CASE WHEN deleted_at IS NULL THEN project_sort_key ELSE NULL END
        ) STORED,
    ADD CONSTRAINT ck_work_item_project_sort_key CHECK (
        project_sort_key ~ '^[0-9]{39}$'
        AND project_sort_key <> repeat('0', 39)
        AND project_sort_key <> repeat('9', 39)
    ),
    ADD CONSTRAINT uq_work_item_project_sort_key
        UNIQUE (project_id, active_project_sort_key)
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_work_item_project_sort_page
    ON yumpoo.work_item (company_id, project_id, project_sort_key ASC, id ASC)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE yumpoo.work_item_project_order IS
    'Transaction lock rows that serialize shared manual Work Item ordering inside a Project.';

COMMENT ON COLUMN yumpoo.work_item.project_sort_key IS
    'Shared Project table order, independent from the Content status-lane Kanban rank.';
