ALTER TABLE yumpoo.product
    ADD CONSTRAINT uq_product_id_company UNIQUE (id, company_id);

CREATE TABLE yumpoo.project_product_link (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    product_id uuid NOT NULL,
    relation_type varchar(16) NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    linked_at timestamptz NOT NULL,
    linked_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    removed_at timestamptz,
    removed_by_user_id uuid,
    remove_reason varchar(500),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_project_product_link_project_company
        FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id),
    CONSTRAINT fk_project_product_link_product_company
        FOREIGN KEY (product_id, company_id)
        REFERENCES yumpoo.product (id, company_id),
    CONSTRAINT fk_project_product_link_linked_by_company
        FOREIGN KEY (linked_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_project_product_link_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_project_product_link_removed_by_company
        FOREIGN KEY (removed_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_project_product_link_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_project_product_link_relation_type CHECK (
        relation_type IN ('DEVELOPMENT', 'DELIVERY', 'SUPPORT', 'USED_BY')
    ),
    CONSTRAINT ck_project_product_link_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_project_product_link_remove_facts CHECK (
        (removed_at IS NULL AND removed_by_user_id IS NULL AND remove_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_by_user_id IS NOT NULL
            AND (remove_reason IS NULL OR (
                char_length(remove_reason) BETWEEN 1 AND 500
                AND remove_reason = btrim(remove_reason)
            )))
    ),
    CONSTRAINT ck_project_product_link_timestamps CHECK (
        updated_at >= linked_at
        AND (removed_at IS NULL OR removed_at >= updated_at)
    )
);

CREATE UNIQUE INDEX uq_project_product_link_active_relation
    ON yumpoo.project_product_link (project_id, product_id, relation_type)
    WHERE removed_at IS NULL;

CREATE UNIQUE INDEX uq_project_product_link_active_primary
    ON yumpoo.project_product_link (project_id)
    WHERE removed_at IS NULL AND is_primary;

CREATE INDEX idx_project_product_link_project_navigation
    ON yumpoo.project_product_link (
        company_id, project_id, is_primary DESC, product_id, relation_type, id
    ) WHERE removed_at IS NULL;

CREATE INDEX idx_project_product_link_product_visibility
    ON yumpoo.project_product_link (company_id, product_id, project_id, relation_type)
    WHERE removed_at IS NULL;

COMMENT ON TABLE yumpoo.project_product_link IS
    'Catalog-owned Product-Project relation; removed rows are immutable history and are never reactivated.';
