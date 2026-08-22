ALTER TABLE yumpoo.content
    ADD CONSTRAINT uq_content_work_item_scope
    UNIQUE (id, company_id, project_id, work_item_type);

CREATE TABLE yumpoo.work_item_project_counter (
    project_id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    last_sequence bigint NOT NULL,
    CONSTRAINT fk_work_item_counter_project_company
        FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id),
    CONSTRAINT ck_work_item_counter_sequence CHECK (last_sequence > 0)
);

CREATE TABLE yumpoo.work_item (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    content_id uuid NOT NULL,
    item_sequence bigint NOT NULL,
    item_no varchar(64) NOT NULL,
    type varchar(16) NOT NULL,
    title varchar(300) NOT NULL,
    status_code varchar(32) NOT NULL,
    status_category varchar(16) NOT NULL,
    priority varchar(16) NOT NULL,
    assignee_user_id uuid,
    reporter_user_id uuid NOT NULL,
    description text,
    notes text,
    timeline_start_date date,
    timeline_end_date date,
    due_date date,
    rank varchar(128),
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    deleted_at timestamptz,
    deleted_by_user_id uuid,
    delete_reason varchar(500),
    CONSTRAINT uq_work_item_project_sequence UNIQUE (project_id, item_sequence),
    CONSTRAINT uq_work_item_company_number UNIQUE (company_id, item_no),
    CONSTRAINT fk_work_item_content_scope
        FOREIGN KEY (content_id, company_id, project_id, type)
        REFERENCES yumpoo.content (id, company_id, project_id, work_item_type),
    CONSTRAINT fk_work_item_assignee_company
        FOREIGN KEY (assignee_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_reporter_company
        FOREIGN KEY (reporter_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_created_by_company
        FOREIGN KEY (created_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_deleted_by_company
        FOREIGN KEY (deleted_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_work_item_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_work_item_sequence CHECK (item_sequence > 0),
    CONSTRAINT ck_work_item_number CHECK (
        item_no ~ '^[A-Z][A-Z0-9_]{1,31}-[1-9][0-9]*$'
    ),
    CONSTRAINT ck_work_item_type CHECK (type IN ('REQUIREMENT', 'TASK', 'DEFECT')),
    CONSTRAINT ck_work_item_title CHECK (
        char_length(title) BETWEEN 1 AND 300 AND title = btrim(title)
    ),
    CONSTRAINT ck_work_item_status_code CHECK (status_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_work_item_status_category CHECK (
        status_category IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELED')
    ),
    CONSTRAINT ck_work_item_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_work_item_timeline CHECK (
        timeline_start_date IS NULL OR timeline_end_date IS NULL
        OR timeline_end_date >= timeline_start_date
    ),
    CONSTRAINT ck_work_item_rank CHECK (rank IS NULL OR char_length(rank) BETWEEN 1 AND 128),
    CONSTRAINT ck_work_item_version CHECK (row_version >= 0),
    CONSTRAINT ck_work_item_delete_facts CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL AND delete_reason IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL
            AND delete_reason IS NOT NULL AND char_length(btrim(delete_reason)) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_work_item_timestamps CHECK (
        updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at)
    )
);

CREATE INDEX idx_work_item_content_page
    ON yumpoo.work_item (company_id, content_id, item_sequence DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_work_item_content_status_page
    ON yumpoo.work_item (company_id, content_id, status_code, item_sequence DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_work_item_project_open
    ON yumpoo.work_item (company_id, project_id)
    WHERE deleted_at IS NULL AND status_category IN ('TODO', 'IN_PROGRESS');

COMMENT ON TABLE yumpoo.work_item IS
    'Authoritative project work facts owned by workitem; item_no is a stable project-code sequence.';
