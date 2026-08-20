CREATE TABLE yumpoo.content (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    name varchar(80) NOT NULL,
    description varchar(500),
    work_item_type varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    default_view_type varchar(16) NOT NULL,
    view_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    applied_template_key varchar(32) NOT NULL,
    applied_template_version integer NOT NULL,
    applied_blueprint_code varchar(32) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    archived_at timestamptz,
    archived_by_user_id uuid,
    CONSTRAINT uq_content_project_code UNIQUE (project_id, code),
    CONSTRAINT fk_content_project_template_scope
        FOREIGN KEY (
            project_id, company_id, applied_template_key, applied_template_version
        ) REFERENCES yumpoo.project (
            id, company_id, template_key, template_version
        ),
    CONSTRAINT fk_content_created_by_company
        FOREIGN KEY (created_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_content_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_content_archived_by_company
        FOREIGN KEY (archived_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_content_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_content_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_content_name CHECK (
        char_length(name) BETWEEN 1 AND 80 AND name = btrim(name)
    ),
    CONSTRAINT ck_content_description CHECK (
        description IS NULL
        OR (char_length(description) BETWEEN 1 AND 500 AND description = btrim(description))
    ),
    CONSTRAINT ck_content_type CHECK (work_item_type IN ('REQUIREMENT', 'TASK', 'DEFECT')),
    CONSTRAINT ck_content_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_content_default_view CHECK (default_view_type IN ('TABLE', 'KANBAN')),
    CONSTRAINT ck_content_view_config CHECK (jsonb_typeof(view_config) = 'object'),
    CONSTRAINT ck_content_applied_template_version CHECK (applied_template_version > 0),
    CONSTRAINT ck_content_applied_blueprint CHECK (
        applied_blueprint_code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_content_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_content_archive_facts CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL AND archived_by_user_id IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_content_timestamps CHECK (
        updated_at >= created_at
        AND (archived_at IS NULL OR archived_at BETWEEN created_at AND updated_at)
    )
);

CREATE INDEX idx_content_project_status_navigation
    ON yumpoo.content (company_id, project_id, status, name, code, id);

COMMENT ON TABLE yumpoo.content IS
    'Single Work Item type container owned by workitem; initial rows retain applied template provenance.';
