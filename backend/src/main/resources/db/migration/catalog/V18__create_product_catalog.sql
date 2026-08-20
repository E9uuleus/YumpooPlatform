CREATE TABLE yumpoo.product (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    product_code varchar(32) NOT NULL,
    name varchar(80) NOT NULL,
    description varchar(500),
    status varchar(16) NOT NULL,
    owner_user_id uuid NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    archived_at timestamptz,
    archived_by_user_id uuid,
    CONSTRAINT uq_product_company_code UNIQUE (company_id, product_code),
    CONSTRAINT fk_product_company FOREIGN KEY (company_id)
        REFERENCES yumpoo.company (id),
    CONSTRAINT fk_product_owner_company
        FOREIGN KEY (owner_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_product_created_by_company
        FOREIGN KEY (created_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_product_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_product_archived_by_company
        FOREIGN KEY (archived_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_product_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_product_code CHECK (
        product_code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_product_name CHECK (
        char_length(name) BETWEEN 1 AND 80 AND name = btrim(name)
    ),
    CONSTRAINT ck_product_description CHECK (
        description IS NULL
        OR (char_length(description) BETWEEN 1 AND 500 AND description = btrim(description))
    ),
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_product_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_product_archive_facts CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL AND archived_by_user_id IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL AND archived_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_product_timestamps CHECK (
        updated_at >= created_at
        AND (archived_at IS NULL OR archived_at BETWEEN created_at AND updated_at)
    )
);

CREATE INDEX idx_product_company_status_navigation
    ON yumpoo.product (company_id, status, name, product_code, id);

CREATE INDEX idx_product_company_owner_status_navigation
    ON yumpoo.product (company_id, owner_user_id, status, name, product_code, id);

COMMENT ON TABLE yumpoo.product IS
    'Company-scoped long-lived product master owned by catalog; owner_user_id is the only PRODUCT_OWNER fact.';
