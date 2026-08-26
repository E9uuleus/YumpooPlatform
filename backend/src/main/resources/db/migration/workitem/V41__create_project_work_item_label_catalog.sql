CREATE TABLE yumpoo.project_work_item_label_catalog (
    project_id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_project_work_item_label_catalog_project
        FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id) ON DELETE CASCADE,
    CONSTRAINT ck_project_work_item_label_catalog_version CHECK (row_version >= 0),
    CONSTRAINT ck_project_work_item_label_catalog_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE yumpoo.project_work_item_status_label (
    project_id uuid NOT NULL,
    company_id uuid NOT NULL,
    status_code varchar(32) NOT NULL,
    display_name varchar(80) NOT NULL,
    color_token varchar(24) NOT NULL,
    status_category varchar(16) NOT NULL,
    sort_order integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    protected_label boolean NOT NULL DEFAULT false,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (project_id, status_code),
    CONSTRAINT fk_project_work_item_status_label_catalog
        FOREIGN KEY (project_id) REFERENCES yumpoo.project_work_item_label_catalog (project_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_project_work_item_status_label_project
        FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id) ON DELETE CASCADE,
    CONSTRAINT ck_project_work_item_status_label_code CHECK (
        status_code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_project_work_item_status_label_name CHECK (
        char_length(display_name) BETWEEN 1 AND 80 AND display_name = btrim(display_name)
    ),
    CONSTRAINT ck_project_work_item_status_label_color CHECK (
        color_token IN ('GREEN', 'TEAL', 'BLUE', 'INDIGO', 'PURPLE', 'MAGENTA',
            'RED', 'ORANGE', 'AMBER', 'LIME', 'CYAN', 'GRAY')
    ),
    CONSTRAINT ck_project_work_item_status_label_category CHECK (
        status_category IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELED')
    ),
    CONSTRAINT ck_project_work_item_status_label_order CHECK (sort_order >= 0),
    CONSTRAINT ck_project_work_item_status_label_delete CHECK (
        deleted_at IS NULL OR (NOT active AND NOT protected_label)
    ),
    CONSTRAINT ck_project_work_item_status_label_timestamps CHECK (
        updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at)
    )
);

CREATE TABLE yumpoo.project_work_item_priority_label (
    project_id uuid NOT NULL,
    company_id uuid NOT NULL,
    priority_code varchar(32) NOT NULL,
    display_name varchar(80) NOT NULL,
    color_token varchar(24) NOT NULL,
    sort_order integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (project_id, priority_code),
    CONSTRAINT fk_project_work_item_priority_label_catalog
        FOREIGN KEY (project_id) REFERENCES yumpoo.project_work_item_label_catalog (project_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_project_work_item_priority_label_project
        FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id) ON DELETE CASCADE,
    CONSTRAINT ck_project_work_item_priority_label_code CHECK (
        priority_code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_project_work_item_priority_label_name CHECK (
        char_length(display_name) BETWEEN 1 AND 80 AND display_name = btrim(display_name)
    ),
    CONSTRAINT ck_project_work_item_priority_label_color CHECK (
        color_token IN ('GREEN', 'TEAL', 'BLUE', 'INDIGO', 'PURPLE', 'MAGENTA',
            'RED', 'ORANGE', 'AMBER', 'LIME', 'CYAN', 'GRAY')
    ),
    CONSTRAINT ck_project_work_item_priority_label_order CHECK (sort_order >= 0),
    CONSTRAINT ck_project_work_item_priority_label_delete CHECK (
        deleted_at IS NULL OR NOT active
    ),
    CONSTRAINT ck_project_work_item_priority_label_timestamps CHECK (
        updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at)
    )
);

CREATE UNIQUE INDEX uq_project_work_item_status_label_order
    ON yumpoo.project_work_item_status_label (project_id, sort_order)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_project_work_item_priority_label_order
    ON yumpoo.project_work_item_priority_label (project_id, sort_order)
    WHERE deleted_at IS NULL;

INSERT INTO yumpoo.project_work_item_label_catalog (project_id, company_id)
SELECT id, company_id FROM yumpoo.project;

INSERT INTO yumpoo.project_work_item_status_label (
    project_id, company_id, status_code, display_name, color_token,
    status_category, sort_order, active, protected_label
)
SELECT project.id, project.company_id, 'NOT_STARTED', '未开始', 'GRAY',
       'TODO', 0, true, true
  FROM yumpoo.project project;

INSERT INTO yumpoo.project_work_item_status_label (
    project_id, company_id, status_code, display_name, color_token,
    status_category, sort_order, active, protected_label
)
SELECT project.id, project.company_id, status.status_code, status.display_name,
       CASE status.status_category
           WHEN 'DONE' THEN 'GREEN'
           WHEN 'CANCELED' THEN 'GRAY'
           WHEN 'IN_PROGRESS' THEN 'ORANGE'
           ELSE 'BLUE'
       END,
       status.status_category, status.sort_order + 100, true, false
  FROM yumpoo.project project
  JOIN yumpoo.project_template_definition template
    ON template.template_key = project.template_key
   AND template.template_version = project.template_version
  JOIN yumpoo.workflow_status_definition status ON status.template_id = template.id
 WHERE status.status_code <> 'NOT_STARTED';

INSERT INTO yumpoo.project_work_item_priority_label (
    project_id, company_id, priority_code, display_name, color_token, sort_order
)
SELECT project.id, project.company_id, seed.priority_code, seed.display_name,
       seed.color_token, seed.sort_order
  FROM yumpoo.project project
 CROSS JOIN (VALUES
    ('LOW', '低', 'BLUE', 10),
    ('MEDIUM', '中', 'TEAL', 20),
    ('HIGH', '高', 'ORANGE', 30),
    ('URGENT', '紧急', 'RED', 40)
 ) AS seed(priority_code, display_name, color_token, sort_order);

ALTER TABLE yumpoo.work_item
    DROP CONSTRAINT ck_work_item_priority,
    ALTER COLUMN priority TYPE varchar(32),
    ADD CONSTRAINT fk_work_item_project_status_label
        FOREIGN KEY (project_id, status_code)
        REFERENCES yumpoo.project_work_item_status_label (project_id, status_code),
    ADD CONSTRAINT fk_work_item_project_priority_label
        FOREIGN KEY (project_id, priority)
        REFERENCES yumpoo.project_work_item_priority_label (project_id, priority_code);

CREATE INDEX idx_project_work_item_status_label_visible
    ON yumpoo.project_work_item_status_label (company_id, project_id, active, sort_order)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_project_work_item_priority_label_visible
    ON yumpoo.project_work_item_priority_label (company_id, project_id, active, sort_order)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE yumpoo.project_work_item_label_catalog IS
    'Project-scoped optimistic-concurrency aggregate for editable Work Item status and priority labels.';
COMMENT ON COLUMN yumpoo.project_work_item_status_label.protected_label IS
    'Protected labels cannot be deactivated or deleted; NOT_STARTED is the universal create default.';
