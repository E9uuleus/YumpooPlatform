CREATE TABLE yumpoo.personal_dashboard (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    name varchar(100) NOT NULL,
    configuration jsonb NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    FOREIGN KEY (owner_user_id, company_id) REFERENCES yumpoo.identity_user(id, company_id),
    CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    CHECK (row_version >= 0),
    CHECK (jsonb_typeof(configuration) = 'object')
);
CREATE INDEX personal_dashboard_owner_idx ON yumpoo.personal_dashboard(company_id, owner_user_id, created_at, id)
    WHERE deleted_at IS NULL;
