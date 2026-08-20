CREATE TABLE yumpoo.workspace (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    code varchar(32) NOT NULL,
    name varchar(80) NOT NULL,
    description varchar(500),
    sort_order integer NOT NULL,
    status varchar(16) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    CONSTRAINT uq_workspace_company_code UNIQUE (company_id, code),
    CONSTRAINT fk_workspace_company FOREIGN KEY (company_id)
        REFERENCES yumpoo.company (id),
    CONSTRAINT fk_workspace_created_by_company
        FOREIGN KEY (created_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_workspace_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_workspace_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_workspace_code CHECK (
        code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_workspace_name CHECK (
        char_length(name) BETWEEN 1 AND 80 AND name = btrim(name)
    ),
    CONSTRAINT ck_workspace_description CHECK (
        description IS NULL
        OR (char_length(description) BETWEEN 1 AND 500 AND description = btrim(description))
    ),
    CONSTRAINT ck_workspace_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_workspace_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_workspace_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_workspace_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX idx_workspace_company_status_navigation
    ON yumpoo.workspace (company_id, status, sort_order, name, id);

COMMENT ON TABLE yumpoo.workspace IS
    'Company-scoped navigation catalog owned by catalog; never an authorization boundary.';
