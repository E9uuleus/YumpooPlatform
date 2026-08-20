ALTER TABLE yumpoo.workspace
    ADD CONSTRAINT uq_workspace_id_company UNIQUE (id, company_id);

CREATE TABLE yumpoo.project (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    project_code varchar(32) NOT NULL,
    name varchar(80) NOT NULL,
    description varchar(500),
    project_type varchar(32) NOT NULL,
    lifecycle varchar(16) NOT NULL,
    owner_user_id uuid NOT NULL,
    template_key varchar(32) NOT NULL,
    template_version integer NOT NULL,
    customer_name varchar(160),
    customer_reference varchar(80),
    delivery_site varchar(160),
    contact_note varchar(500),
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL,
    activated_at timestamptz,
    archived_at timestamptz,
    CONSTRAINT uq_project_company_code UNIQUE (company_id, project_code),
    CONSTRAINT uq_project_id_company UNIQUE (id, company_id),
    CONSTRAINT uq_project_template_scope UNIQUE (
        id, company_id, template_key, template_version
    ),
    CONSTRAINT fk_project_company FOREIGN KEY (company_id)
        REFERENCES yumpoo.company (id),
    CONSTRAINT fk_project_workspace_company
        FOREIGN KEY (workspace_id, company_id)
        REFERENCES yumpoo.workspace (id, company_id),
    CONSTRAINT fk_project_owner_company
        FOREIGN KEY (owner_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_project_template_reference
        FOREIGN KEY (template_key, template_version)
        REFERENCES yumpoo.project_template_definition (template_key, template_version),
    CONSTRAINT fk_project_created_by_company
        FOREIGN KEY (created_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_project_updated_by_company
        FOREIGN KEY (updated_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_project_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_project_code CHECK (
        project_code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_project_name CHECK (
        char_length(name) BETWEEN 1 AND 80 AND name = btrim(name)
    ),
    CONSTRAINT ck_project_description CHECK (
        description IS NULL
        OR (char_length(description) BETWEEN 1 AND 500 AND description = btrim(description))
    ),
    CONSTRAINT ck_project_type CHECK (
        project_type IN ('PRODUCT_DEVELOPMENT', 'PRE_SALES', 'IMPLEMENTATION', 'HYPERCARE')
    ),
    CONSTRAINT ck_project_template_type CHECK (
        (project_type = 'PRODUCT_DEVELOPMENT' AND template_key = 'RND')
        OR (project_type = 'PRE_SALES' AND template_key = 'PRE_SALES')
        OR (project_type = 'IMPLEMENTATION' AND template_key = 'IMPLEMENTATION')
        OR (project_type = 'HYPERCARE' AND template_key = 'HYPERCARE')
    ),
    CONSTRAINT ck_project_template_version CHECK (template_version > 0),
    CONSTRAINT ck_project_lifecycle CHECK (lifecycle IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_project_customer_name CHECK (
        customer_name IS NULL
        OR (char_length(customer_name) BETWEEN 1 AND 160 AND customer_name = btrim(customer_name))
    ),
    CONSTRAINT ck_project_customer_reference CHECK (
        customer_reference IS NULL
        OR (char_length(customer_reference) BETWEEN 1 AND 80
            AND customer_reference = btrim(customer_reference))
    ),
    CONSTRAINT ck_project_delivery_site CHECK (
        delivery_site IS NULL
        OR (char_length(delivery_site) BETWEEN 1 AND 160 AND delivery_site = btrim(delivery_site))
    ),
    CONSTRAINT ck_project_contact_note CHECK (
        contact_note IS NULL
        OR (char_length(contact_note) BETWEEN 1 AND 500 AND contact_note = btrim(contact_note))
    ),
    CONSTRAINT ck_project_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_project_lifecycle_times CHECK (
        (lifecycle = 'DRAFT' AND activated_at IS NULL AND archived_at IS NULL)
        OR (lifecycle = 'ACTIVE' AND activated_at IS NOT NULL AND archived_at IS NULL)
        OR (lifecycle = 'ARCHIVED' AND activated_at IS NOT NULL AND archived_at IS NOT NULL)
    ),
    CONSTRAINT ck_project_timestamps CHECK (
        updated_at >= created_at
        AND (activated_at IS NULL OR activated_at BETWEEN created_at AND updated_at)
        AND (archived_at IS NULL OR archived_at BETWEEN activated_at AND updated_at)
    )
);

CREATE TABLE yumpoo.project_membership (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    joined_at timestamptz NOT NULL,
    joined_by_user_id uuid NOT NULL,
    removed_at timestamptz,
    removed_by_user_id uuid,
    remove_reason varchar(500),
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_project_membership_user UNIQUE (project_id, user_id),
    CONSTRAINT fk_project_membership_project_company
        FOREIGN KEY (project_id, company_id)
        REFERENCES yumpoo.project (id, company_id),
    CONSTRAINT fk_project_membership_user_company
        FOREIGN KEY (user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_project_membership_joined_by_company
        FOREIGN KEY (joined_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT fk_project_membership_removed_by_company
        FOREIGN KEY (removed_by_user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_project_membership_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_project_membership_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT ck_project_membership_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_project_membership_remove_facts CHECK (
        (status = 'ACTIVE'
            AND removed_at IS NULL
            AND removed_by_user_id IS NULL
            AND remove_reason IS NULL)
        OR (status = 'REMOVED'
            AND removed_at IS NOT NULL
            AND removed_by_user_id IS NOT NULL
            AND char_length(remove_reason) BETWEEN 1 AND 500
            AND remove_reason = btrim(remove_reason)
            AND removed_at >= joined_at)
    )
);

CREATE OR REPLACE FUNCTION yumpoo.assert_project_active_owner_membership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_project_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'project' THEN
        target_project_id := COALESCE(NEW.id, OLD.id);
    ELSE
        target_project_id := COALESCE(NEW.project_id, OLD.project_id);
    END IF;

    IF EXISTS (SELECT 1 FROM yumpoo.project WHERE id = target_project_id)
       AND NOT EXISTS (
           SELECT 1
             FROM yumpoo.project project
             JOIN yumpoo.project_membership membership
               ON membership.project_id = project.id
              AND membership.user_id = project.owner_user_id
              AND membership.status = 'ACTIVE'
            WHERE project.id = target_project_id
       ) THEN
        RAISE EXCEPTION 'project owner must have an active membership'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_project_active_owner
    AFTER INSERT OR UPDATE ON yumpoo.project
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION yumpoo.assert_project_active_owner_membership();

CREATE CONSTRAINT TRIGGER trg_project_membership_active_owner
    AFTER INSERT OR UPDATE OR DELETE ON yumpoo.project_membership
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION yumpoo.assert_project_active_owner_membership();

CREATE INDEX idx_project_company_workspace_lifecycle
    ON yumpoo.project (company_id, workspace_id, lifecycle, name, project_code, id);

CREATE INDEX idx_project_company_owner_lifecycle
    ON yumpoo.project (company_id, owner_user_id, lifecycle, name, project_code, id);

CREATE INDEX idx_project_membership_active_user
    ON yumpoo.project_membership (company_id, user_id, project_id)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE yumpoo.project IS
    'Company-scoped Project aggregate owned by catalog; template key/version is immutable.';
COMMENT ON TABLE yumpoo.project_membership IS
    'Project membership truth owned by catalog; the current owner must have an ACTIVE row.';
