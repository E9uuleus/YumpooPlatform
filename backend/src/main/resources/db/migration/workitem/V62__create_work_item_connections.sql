CREATE TABLE yumpoo.work_item_connect_column_catalog (
    project_id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_connect_column_catalog_project
        FOREIGN KEY (project_id, company_id) REFERENCES yumpoo.project (id, company_id),
    CONSTRAINT ck_connect_column_catalog_version CHECK (row_version >= 0)
);

CREATE TABLE yumpoo.work_item_connect_column (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    name varchar(40) NOT NULL,
    normalized_name varchar(40) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_by_user_id uuid,
    deleted_at timestamptz,
    CONSTRAINT uq_connect_column_scope UNIQUE (id, company_id, project_id),
    CONSTRAINT fk_connect_column_catalog
        FOREIGN KEY (project_id) REFERENCES yumpoo.work_item_connect_column_catalog (project_id),
    CONSTRAINT fk_connect_column_created_by_company
        FOREIGN KEY (created_by_user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_connect_column_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_connect_column_deleted_by_company
        FOREIGN KEY (deleted_by_user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_connect_column_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT ck_connect_column_name CHECK (
        char_length(name) BETWEEN 1 AND 40 AND name = btrim(name)
        AND normalized_name = lower(name)),
    CONSTRAINT ck_connect_column_version CHECK (row_version >= 0),
    CONSTRAINT ck_connect_column_delete_facts CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL)),
    CONSTRAINT ck_connect_column_timestamps CHECK (
        updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at))
);
CREATE UNIQUE INDEX uq_connect_column_active_name
    ON yumpoo.work_item_connect_column (company_id, project_id, normalized_name)
    WHERE deleted_at IS NULL;

CREATE TABLE yumpoo.work_item_connect_column_target (
    column_id uuid NOT NULL REFERENCES yumpoo.work_item_connect_column (id),
    company_id uuid NOT NULL,
    target_project_id uuid NOT NULL,
    PRIMARY KEY (column_id, target_project_id),
    CONSTRAINT fk_connect_column_target_project
        FOREIGN KEY (target_project_id, company_id) REFERENCES yumpoo.project (id, company_id)
);
CREATE INDEX idx_connect_column_target_project
    ON yumpoo.work_item_connect_column_target (company_id, target_project_id);

CREATE TABLE yumpoo.work_item_connection (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    column_id uuid NOT NULL,
    source_project_id uuid NOT NULL,
    source_work_item_id uuid NOT NULL,
    target_project_id uuid NOT NULL,
    target_work_item_id uuid NOT NULL,
    origin varchar(16) NOT NULL,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    deleted_by_user_id uuid,
    deleted_at timestamptz,
    delete_reason varchar(16),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_connection_column_scope
        FOREIGN KEY (column_id, company_id, source_project_id)
        REFERENCES yumpoo.work_item_connect_column (id, company_id, project_id),
    CONSTRAINT fk_connection_source_scope
        FOREIGN KEY (source_work_item_id, company_id, source_project_id)
        REFERENCES yumpoo.work_item (id, company_id, project_id),
    CONSTRAINT fk_connection_target_scope
        FOREIGN KEY (target_work_item_id, company_id, target_project_id)
        REFERENCES yumpoo.work_item (id, company_id, project_id),
    CONSTRAINT fk_connection_created_by_company
        FOREIGN KEY (created_by_user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_connection_deleted_by_company
        FOREIGN KEY (deleted_by_user_id, company_id) REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_connection_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT ck_connection_cross_project CHECK (source_project_id <> target_project_id),
    CONSTRAINT ck_connection_origin CHECK (origin IN ('LINKED', 'CREATED')),
    CONSTRAINT ck_connection_delete_facts CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL AND delete_reason IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL AND delete_reason IS NOT NULL
            AND delete_reason IN ('UNLINKED', 'COLUMN_DELETED'))),
    CONSTRAINT ck_connection_version CHECK (row_version >= 0),
    CONSTRAINT ck_connection_timestamps CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);
CREATE UNIQUE INDEX uq_connection_active_pair
    ON yumpoo.work_item_connection (company_id, column_id, source_work_item_id, target_work_item_id)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_connection_active_source
    ON yumpoo.work_item_connection (company_id, source_work_item_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_connection_active_target
    ON yumpoo.work_item_connection (company_id, target_work_item_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_connection_active_column_target
    ON yumpoo.work_item_connection (company_id, column_id, target_project_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE yumpoo.work_item_connect_column_catalog IS
    'Project connection-column catalog version and serialization lock; no persisted column order.';
COMMENT ON TABLE yumpoo.work_item_connect_column IS
    'Named project connection columns, independently governed from ordinary Work Item relations.';
COMMENT ON TABLE yumpoo.work_item_connect_column_target IS
    'Allowed target projects for each connection column; replaced under the column write lock.';
COMMENT ON TABLE yumpoo.work_item_connection IS
    'Directed cross-project connections with minimal-card disclosure; removed facts are never revived.';
