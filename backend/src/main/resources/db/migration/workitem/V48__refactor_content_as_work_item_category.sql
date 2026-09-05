ALTER TABLE yumpoo.project_template_content_blueprint DISABLE TRIGGER trg_project_template_content_immutable;

ALTER TABLE yumpoo.project_template_content_blueprint
    ADD COLUMN color_token varchar(24) NOT NULL DEFAULT 'BRIGHT_BLUE';

UPDATE yumpoo.project_template_content_blueprint
   SET color_token = CASE content_code
       WHEN 'REQUIREMENTS' THEN 'BRIGHT_BLUE'
       WHEN 'TASKS' THEN 'BRIGHT_GREEN'
       WHEN 'DEFECTS' THEN 'DARK_RED'
       ELSE 'BRIGHT_BLUE'
   END;

ALTER TABLE yumpoo.project_template_content_blueprint
    DROP CONSTRAINT uq_project_template_content_type,
    DROP CONSTRAINT ck_project_template_content_type,
    DROP CONSTRAINT ck_project_template_content_view,
    DROP COLUMN work_item_type,
    DROP COLUMN default_view_type,
    ALTER COLUMN color_token DROP DEFAULT,
    ADD CONSTRAINT ck_project_template_content_color CHECK (
        color_token IN (
            'BRIGHT_GREEN', 'SALADISH', 'EGG_YOLK', 'DARK_ORANGE',
            'PEACH', 'SUNSET', 'DARK_RED', 'SOFIA_PINK', 'LIPSTICK', 'BUBBLE',
            'DARK_PURPLE', 'BERRY', 'DARK_INDIGO', 'INDIGO', 'NAVY', 'BRIGHT_BLUE',
            'AQUAMARINE', 'CHILI_BLUE', 'RIVER', 'WINTER', 'AMERICAN_GRAY', 'BLACKISH',
            'BROWN', 'ORCHID', 'TAN', 'SKY', 'COFFEE', 'ROYAL', 'TEAL', 'LAVENDER',
            'STEEL', 'LILAC', 'PECAN', 'GREEN', 'BLUE', 'PURPLE', 'MAGENTA', 'RED',
            'ORANGE', 'AMBER', 'LIME', 'CYAN', 'GRAY'
        )
    );

ALTER TABLE yumpoo.project_template_content_blueprint ENABLE TRIGGER trg_project_template_content_immutable;

DROP INDEX IF EXISTS yumpoo.idx_content_project_status_navigation;
ALTER TABLE yumpoo.work_item DROP CONSTRAINT fk_work_item_content_scope;
ALTER TABLE yumpoo.content DROP CONSTRAINT uq_content_work_item_scope;

ALTER TABLE yumpoo.content
    ADD COLUMN color_token varchar(24),
    ADD COLUMN sort_order integer,
    ADD COLUMN active boolean,
    ADD COLUMN protected_content boolean NOT NULL DEFAULT false,
    ADD COLUMN ever_used boolean NOT NULL DEFAULT false,
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by_user_id uuid;

WITH ordered AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY project_id
               ORDER BY CASE code WHEN 'REQUIREMENTS' THEN 1 WHEN 'TASKS' THEN 2
                                  WHEN 'DEFECTS' THEN 3 ELSE 4 END,
                        name, code, id
           ) * 10 AS new_sort_order
      FROM yumpoo.content
)
UPDATE yumpoo.content content
   SET color_token = CASE content.code
           WHEN 'REQUIREMENTS' THEN 'BRIGHT_BLUE'
           WHEN 'TASKS' THEN 'BRIGHT_GREEN'
           WHEN 'DEFECTS' THEN 'DARK_RED'
           WHEN 'REQUIREMENT' THEN 'BRIGHT_BLUE'
           WHEN 'TASK' THEN 'BRIGHT_GREEN'
           WHEN 'DEFECT' THEN 'DARK_RED'
           ELSE CASE content.work_item_type
               WHEN 'TASK' THEN 'BRIGHT_GREEN'
               WHEN 'DEFECT' THEN 'DARK_RED'
               ELSE 'BRIGHT_BLUE'
           END
       END,
       sort_order = ordered.new_sort_order,
       active = content.status = 'ACTIVE',
       protected_content = content.code IN ('REQUIREMENTS', 'TASKS', 'DEFECTS'),
       ever_used = EXISTS (SELECT 1 FROM yumpoo.work_item item WHERE item.content_id = content.id)
  FROM ordered
 WHERE ordered.id = content.id;

WITH projects_without_active AS (
    SELECT project_id FROM yumpoo.content GROUP BY project_id HAVING bool_or(active) = false
), first_content AS (
    SELECT DISTINCT ON (content.project_id) content.id
      FROM yumpoo.content content
      JOIN projects_without_active project ON project.project_id = content.project_id
     ORDER BY content.project_id, content.protected_content DESC, content.sort_order, content.id
)
UPDATE yumpoo.content content SET active = true
  FROM first_content WHERE first_content.id = content.id;

ALTER TABLE yumpoo.content
    DROP CONSTRAINT fk_content_project_template_scope,
    DROP CONSTRAINT fk_content_archived_by_company,
    DROP CONSTRAINT ck_content_description,
    DROP CONSTRAINT ck_content_type,
    DROP CONSTRAINT ck_content_status,
    DROP CONSTRAINT ck_content_default_view,
    DROP CONSTRAINT ck_content_view_config,
    DROP CONSTRAINT ck_content_applied_template_version,
    DROP CONSTRAINT ck_content_applied_blueprint,
    DROP CONSTRAINT ck_content_archive_facts,
    DROP CONSTRAINT ck_content_timestamps,
    DROP COLUMN description,
    DROP COLUMN work_item_type,
    DROP COLUMN status,
    DROP COLUMN default_view_type,
    DROP COLUMN view_config,
    DROP COLUMN applied_template_key,
    DROP COLUMN applied_template_version,
    DROP COLUMN applied_blueprint_code,
    DROP COLUMN archived_at,
    DROP COLUMN archived_by_user_id,
    ALTER COLUMN color_token SET NOT NULL,
    ALTER COLUMN sort_order SET NOT NULL,
    ALTER COLUMN active SET NOT NULL,
    ALTER COLUMN protected_content DROP DEFAULT,
    ALTER COLUMN ever_used DROP DEFAULT,
    ADD CONSTRAINT uq_content_scope UNIQUE (id, company_id, project_id),
    ADD CONSTRAINT fk_content_deleted_by_company FOREIGN KEY (deleted_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    ADD CONSTRAINT ck_content_color CHECK (
        color_token IN (
            'BRIGHT_GREEN', 'SALADISH', 'EGG_YOLK', 'DARK_ORANGE',
            'PEACH', 'SUNSET', 'DARK_RED', 'SOFIA_PINK', 'LIPSTICK', 'BUBBLE',
            'DARK_PURPLE', 'BERRY', 'DARK_INDIGO', 'INDIGO', 'NAVY', 'BRIGHT_BLUE',
            'AQUAMARINE', 'CHILI_BLUE', 'RIVER', 'WINTER', 'AMERICAN_GRAY', 'BLACKISH',
            'BROWN', 'ORCHID', 'TAN', 'SKY', 'COFFEE', 'ROYAL', 'TEAL', 'LAVENDER',
            'STEEL', 'LILAC', 'PECAN', 'GREEN', 'BLUE', 'PURPLE', 'MAGENTA', 'RED',
            'ORANGE', 'AMBER', 'LIME', 'CYAN', 'GRAY'
        )
    ),
    ADD CONSTRAINT ck_content_sort_order CHECK (sort_order > 0),
    ADD CONSTRAINT ck_content_delete_facts CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL
            AND protected_content = false AND ever_used = false)
    ),
    ADD CONSTRAINT ck_content_timestamps CHECK (
        updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at)
    );

CREATE INDEX idx_content_project_catalog
    ON yumpoo.content (company_id, project_id, active DESC, sort_order, id)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_content_project_sort_active
    ON yumpoo.content (project_id, sort_order)
    WHERE deleted_at IS NULL;

CREATE TABLE yumpoo.content_catalog_version (
    project_id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_content_catalog_version_project FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id),
    CONSTRAINT ck_content_catalog_version CHECK (row_version >= 0)
);

INSERT INTO yumpoo.content_catalog_version (project_id, company_id)
SELECT id, company_id FROM yumpoo.project;

ALTER TABLE yumpoo.work_item
    DROP CONSTRAINT ck_work_item_type,
    DROP COLUMN type,
    ADD CONSTRAINT fk_work_item_content_scope
        FOREIGN KEY (content_id, company_id, project_id)
        REFERENCES yumpoo.content (id, company_id, project_id);

ALTER TABLE yumpoo.work_item_update DROP COLUMN content_id;

DROP TABLE yumpoo.work_item_rank_lane;
CREATE TABLE yumpoo.work_item_rank_lane (
    project_id uuid NOT NULL,
    company_id uuid NOT NULL,
    status_code varchar(32) NOT NULL,
    CONSTRAINT pk_work_item_rank_lane PRIMARY KEY (project_id, status_code),
    CONSTRAINT fk_work_item_rank_lane_project FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id) ON DELETE CASCADE,
    CONSTRAINT ck_work_item_rank_lane_status CHECK (status_code ~ '^[A-Z][A-Z0-9_]{1,31}$')
);

INSERT INTO yumpoo.work_item_rank_lane (project_id, company_id, status_code)
SELECT DISTINCT project_id, company_id, status_code FROM yumpoo.work_item;

ALTER TABLE yumpoo.work_item DROP CONSTRAINT uq_work_item_active_lane_rank;
UPDATE yumpoo.work_item SET rank = project_sort_key;
ALTER TABLE yumpoo.work_item
    ADD CONSTRAINT uq_work_item_active_lane_rank
        UNIQUE (project_id, status_code, active_lane_rank)
        DEFERRABLE INITIALLY DEFERRED;

DROP INDEX IF EXISTS yumpoo.idx_work_item_content_status_rank_page;
CREATE INDEX idx_work_item_project_status_rank_page
    ON yumpoo.work_item (company_id, project_id, status_code, rank, id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE yumpoo.content IS
    'Project-scoped Work Item category catalog; view configuration belongs to project Work Item views.';
COMMENT ON TABLE yumpoo.work_item_rank_lane IS
    'Transaction lock rows that serialize Work Item ordering inside a project status lane.';
