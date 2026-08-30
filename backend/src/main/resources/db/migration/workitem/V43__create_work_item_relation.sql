ALTER TABLE yumpoo.work_item
    ADD CONSTRAINT uq_work_item_relation_scope
    UNIQUE (id, company_id, project_id);

CREATE TABLE yumpoo.work_item_relation (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    relation_type varchar(32) NOT NULL,
    left_work_item_id uuid NOT NULL,
    right_work_item_id uuid NOT NULL,
    left_project_id uuid NOT NULL,
    right_project_id uuid NOT NULL,
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    deleted_by_user_id uuid,
    deleted_at timestamptz,
    delete_reason varchar(500),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_work_item_relation_left_scope
        FOREIGN KEY (left_work_item_id, company_id, left_project_id)
        REFERENCES yumpoo.work_item (id, company_id, project_id),
    CONSTRAINT fk_work_item_relation_right_scope
        FOREIGN KEY (right_work_item_id, company_id, right_project_id)
        REFERENCES yumpoo.work_item (id, company_id, project_id),
    CONSTRAINT fk_work_item_relation_created_by_company
        FOREIGN KEY (created_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_relation_deleted_by_company
        FOREIGN KEY (deleted_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_work_item_relation_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_work_item_relation_type CHECK (
        relation_type IN ('PARENT_CHILD', 'RELATED', 'BLOCKS', 'SOURCE', 'DUPLICATE')
    ),
    CONSTRAINT ck_work_item_relation_not_self CHECK (left_work_item_id <> right_work_item_id),
    CONSTRAINT ck_work_item_relation_parent_child_project CHECK (
        relation_type <> 'PARENT_CHILD' OR left_project_id = right_project_id
    ),
    CONSTRAINT ck_work_item_relation_version CHECK (row_version >= 0),
    CONSTRAINT ck_work_item_relation_delete_facts CHECK (
        (deleted_at IS NULL AND deleted_by_user_id IS NULL AND delete_reason IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL
            AND delete_reason IS NOT NULL
            AND char_length(btrim(delete_reason)) BETWEEN 1 AND 500)
    ),
    CONSTRAINT ck_work_item_relation_timestamps CHECK (
        deleted_at IS NULL OR deleted_at >= created_at
    )
);

CREATE UNIQUE INDEX uq_work_item_relation_active_pair
    ON yumpoo.work_item_relation (
        company_id, relation_type, left_work_item_id, right_work_item_id
    )
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_work_item_relation_active_parent
    ON yumpoo.work_item_relation (company_id, right_work_item_id)
    WHERE relation_type = 'PARENT_CHILD' AND deleted_at IS NULL;

CREATE INDEX idx_work_item_relation_active_left
    ON yumpoo.work_item_relation (company_id, left_work_item_id, right_work_item_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_work_item_relation_active_right
    ON yumpoo.work_item_relation (company_id, right_work_item_id, left_work_item_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE yumpoo.work_item_relation IS
    'Authoritative ordinary Work Item relations; PARENT_CHILD stores parent on the left and child on the right.';
