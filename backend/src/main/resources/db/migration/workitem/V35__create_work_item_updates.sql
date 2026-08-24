ALTER TABLE yumpoo.work_item
    ADD CONSTRAINT uq_work_item_update_parent_scope UNIQUE (id, company_id, project_id);

CREATE TABLE yumpoo.work_item_update (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    content_id uuid NOT NULL,
    work_item_id uuid NOT NULL,
    author_user_id uuid NOT NULL,
    author_display_name varchar(200) NOT NULL,
    body_html text,
    body_text text,
    status varchar(16) NOT NULL,
    edit_deadline_at timestamptz NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    edited_at timestamptz,
    edited_by_user_id uuid,
    deleted_at timestamptz,
    deleted_by_user_id uuid,
    delete_reason varchar(500),
    CONSTRAINT uq_work_item_update_company_id UNIQUE (id, company_id),
    CONSTRAINT fk_work_item_update_parent FOREIGN KEY (work_item_id, company_id, project_id)
        REFERENCES yumpoo.work_item (id, company_id, project_id),
    CONSTRAINT fk_work_item_update_author FOREIGN KEY (author_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_update_editor FOREIGN KEY (edited_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_work_item_update_deleter FOREIGN KEY (deleted_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_work_item_update_status CHECK (status IN ('PUBLISHED', 'EDITED', 'DELETED')),
    CONSTRAINT ck_work_item_update_body CHECK (
        (status = 'DELETED' AND body_html IS NULL AND body_text IS NULL)
        OR (status <> 'DELETED' AND body_html IS NOT NULL AND body_text IS NOT NULL
            AND char_length(body_html) BETWEEN 1 AND 65536
            AND char_length(body_text) BETWEEN 1 AND 16384)
    ),
    CONSTRAINT ck_work_item_update_version CHECK (row_version >= 0),
    CONSTRAINT ck_work_item_update_edit_deadline CHECK (edit_deadline_at = created_at + interval '15 minutes'),
    CONSTRAINT ck_work_item_update_edit_facts CHECK (
        (status = 'PUBLISHED' AND edited_at IS NULL AND edited_by_user_id IS NULL)
        OR status IN ('EDITED', 'DELETED')
    ),
    CONSTRAINT ck_work_item_update_delete_facts CHECK (
        (status <> 'DELETED' AND deleted_at IS NULL AND deleted_by_user_id IS NULL AND delete_reason IS NULL)
        OR (status = 'DELETED' AND deleted_at IS NOT NULL AND deleted_by_user_id IS NOT NULL
            AND delete_reason IS NOT NULL AND char_length(btrim(delete_reason)) BETWEEN 1 AND 500)
    )
);

CREATE TABLE yumpoo.work_item_update_mention (
    update_id uuid NOT NULL,
    company_id uuid NOT NULL,
    mentioned_user_id uuid NOT NULL,
    mentioned_display_name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (update_id, mentioned_user_id),
    CONSTRAINT fk_work_item_update_mention_update FOREIGN KEY (update_id, company_id)
        REFERENCES yumpoo.work_item_update (id, company_id) ON DELETE CASCADE,
    CONSTRAINT fk_work_item_update_mention_user FOREIGN KEY (mentioned_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id)
);

CREATE INDEX idx_work_item_update_page
    ON yumpoo.work_item_update (company_id, work_item_id, created_at, id);

COMMENT ON TABLE yumpoo.work_item_update IS
    'Independent Work Item discussion stream; publishing never changes the parent Work Item version.';
