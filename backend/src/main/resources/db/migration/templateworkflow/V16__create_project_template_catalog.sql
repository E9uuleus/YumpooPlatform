CREATE TABLE yumpoo.project_template_definition (
    id uuid PRIMARY KEY,
    template_key varchar(32) NOT NULL,
    template_version integer NOT NULL,
    version_code varchar(48) NOT NULL,
    project_type varchar(32) NOT NULL,
    display_name varchar(80) NOT NULL,
    lifecycle_status varchar(16) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    published_at timestamptz,
    published_by_actor_type varchar(16),
    published_by_user_id uuid,
    published_by_system_code varchar(64),
    retired_at timestamptz,
    retired_by_user_id uuid,
    retire_reason varchar(160),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT uq_project_template_key_version UNIQUE (template_key, template_version),
    CONSTRAINT uq_project_template_version_code UNIQUE (version_code),
    CONSTRAINT ck_project_template_id_v4 CHECK (
        id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_project_template_identity CHECK (
        (template_key = 'RND' AND project_type = 'PRODUCT_DEVELOPMENT')
        OR (template_key = 'PRE_SALES' AND project_type = 'PRE_SALES')
        OR (template_key = 'IMPLEMENTATION' AND project_type = 'IMPLEMENTATION')
        OR (template_key = 'HYPERCARE' AND project_type = 'HYPERCARE')
    ),
    CONSTRAINT ck_project_template_version CHECK (template_version > 0),
    CONSTRAINT ck_project_template_version_code CHECK (
        version_code = template_key || '_V' || template_version::text
    ),
    CONSTRAINT ck_project_template_display_name CHECK (
        char_length(display_name) BETWEEN 1 AND 80 AND display_name = btrim(display_name)
    ),
    CONSTRAINT ck_project_template_lifecycle CHECK (
        lifecycle_status IN ('DRAFT', 'PUBLISHED', 'RETIRED')
    ),
    CONSTRAINT ck_project_template_publish_actor CHECK (
        (lifecycle_status = 'DRAFT'
            AND published_at IS NULL
            AND published_by_actor_type IS NULL
            AND published_by_user_id IS NULL
            AND published_by_system_code IS NULL)
        OR (lifecycle_status IN ('PUBLISHED', 'RETIRED')
            AND published_at IS NOT NULL
            AND (
                (published_by_actor_type = 'USER'
                    AND published_by_user_id IS NOT NULL
                    AND published_by_system_code IS NULL)
                OR (published_by_actor_type = 'SYSTEM'
                    AND published_by_user_id IS NULL
                    AND published_by_system_code ~ '^[A-Z][A-Z0-9_.:-]{1,63}$')
            ))
    ),
    CONSTRAINT ck_project_template_retirement CHECK (
        (lifecycle_status IN ('DRAFT', 'PUBLISHED')
            AND retired_at IS NULL
            AND retired_by_user_id IS NULL
            AND retire_reason IS NULL)
        OR (lifecycle_status = 'RETIRED'
            AND retired_at IS NOT NULL
            AND retired_by_user_id IS NOT NULL
            AND char_length(retire_reason) BETWEEN 1 AND 160
            AND retire_reason = btrim(retire_reason)
            AND retired_at >= published_at)
    ),
    CONSTRAINT ck_project_template_row_version CHECK (row_version >= 0),
    CONSTRAINT ck_project_template_timestamps CHECK (
        updated_at >= created_at
        AND (published_at IS NULL OR published_at BETWEEN created_at AND updated_at)
        AND (retired_at IS NULL OR retired_at BETWEEN published_at AND updated_at)
    )
);

CREATE TABLE yumpoo.project_template_content_blueprint (
    template_id uuid NOT NULL,
    content_code varchar(32) NOT NULL,
    display_name varchar(80) NOT NULL,
    work_item_type varchar(16) NOT NULL,
    default_view_type varchar(16) NOT NULL,
    sort_order integer NOT NULL,
    PRIMARY KEY (template_id, content_code),
    CONSTRAINT fk_project_template_content_template
        FOREIGN KEY (template_id) REFERENCES yumpoo.project_template_definition (id) ON DELETE CASCADE,
    CONSTRAINT uq_project_template_content_type UNIQUE (template_id, work_item_type),
    CONSTRAINT uq_project_template_content_order UNIQUE (template_id, sort_order),
    CONSTRAINT ck_project_template_content_code CHECK (
        content_code ~ '^[A-Z][A-Z0-9_]{1,31}$'
    ),
    CONSTRAINT ck_project_template_content_name CHECK (
        char_length(display_name) BETWEEN 1 AND 80 AND display_name = btrim(display_name)
    ),
    CONSTRAINT ck_project_template_content_type CHECK (
        work_item_type IN ('REQUIREMENT', 'TASK', 'DEFECT')
    ),
    CONSTRAINT ck_project_template_content_view CHECK (default_view_type = 'TABLE'),
    CONSTRAINT ck_project_template_content_order CHECK (sort_order > 0)
);

CREATE TABLE yumpoo.workflow_status_definition (
    template_id uuid NOT NULL,
    status_code varchar(32) NOT NULL,
    display_name varchar(80) NOT NULL,
    status_category varchar(16) NOT NULL,
    sort_order integer NOT NULL,
    is_initial boolean NOT NULL DEFAULT false,
    is_terminal boolean NOT NULL DEFAULT false,
    PRIMARY KEY (template_id, status_code),
    CONSTRAINT fk_workflow_status_template
        FOREIGN KEY (template_id) REFERENCES yumpoo.project_template_definition (id) ON DELETE CASCADE,
    CONSTRAINT uq_workflow_status_order UNIQUE (template_id, sort_order),
    CONSTRAINT ck_workflow_status_code CHECK (status_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_workflow_status_name CHECK (
        char_length(display_name) BETWEEN 1 AND 80 AND display_name = btrim(display_name)
    ),
    CONSTRAINT ck_workflow_status_category CHECK (
        status_category IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELED')
    ),
    CONSTRAINT ck_workflow_status_order CHECK (sort_order > 0),
    CONSTRAINT ck_workflow_terminal_category CHECK (
        NOT is_terminal OR status_category IN ('DONE', 'CANCELED')
    )
);

CREATE UNIQUE INDEX uq_workflow_status_initial
    ON yumpoo.workflow_status_definition (template_id)
    WHERE is_initial;

CREATE TABLE yumpoo.workflow_transition_definition (
    template_id uuid NOT NULL,
    from_status varchar(32) NOT NULL,
    to_status varchar(32) NOT NULL,
    required_permission varchar(24) NOT NULL,
    requires_resolution boolean NOT NULL DEFAULT false,
    PRIMARY KEY (template_id, from_status, to_status),
    CONSTRAINT fk_workflow_transition_template
        FOREIGN KEY (template_id) REFERENCES yumpoo.project_template_definition (id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_transition_from
        FOREIGN KEY (template_id, from_status)
        REFERENCES yumpoo.workflow_status_definition (template_id, status_code),
    CONSTRAINT fk_workflow_transition_to
        FOREIGN KEY (template_id, to_status)
        REFERENCES yumpoo.workflow_status_definition (template_id, status_code),
    CONSTRAINT ck_workflow_transition_distinct CHECK (from_status <> to_status),
    CONSTRAINT ck_workflow_transition_permission CHECK (required_permission = 'MEMBER')
);

CREATE OR REPLACE FUNCTION yumpoo.guard_project_template_structure_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status varchar(16);
BEGIN
    SELECT lifecycle_status
      INTO parent_status
      FROM yumpoo.project_template_definition
     WHERE id = COALESCE(NEW.template_id, OLD.template_id);
    IF parent_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'published project template structure is immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_project_template_content_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON yumpoo.project_template_content_blueprint
    FOR EACH ROW EXECUTE FUNCTION yumpoo.guard_project_template_structure_mutation();

CREATE TRIGGER trg_workflow_status_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON yumpoo.workflow_status_definition
    FOR EACH ROW EXECUTE FUNCTION yumpoo.guard_project_template_structure_mutation();

CREATE TRIGGER trg_workflow_transition_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON yumpoo.workflow_transition_definition
    FOR EACH ROW EXECUTE FUNCTION yumpoo.guard_project_template_structure_mutation();

CREATE OR REPLACE FUNCTION yumpoo.guard_project_template_definition_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.lifecycle_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'published project template version cannot be deleted'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF NEW.id <> OLD.id
        OR NEW.template_key <> OLD.template_key
        OR NEW.template_version <> OLD.template_version
        OR NEW.version_code <> OLD.version_code
        OR NEW.project_type <> OLD.project_type
        OR NEW.display_name <> OLD.display_name
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'project template identity is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.lifecycle_status = 'DRAFT' AND NEW.lifecycle_status = 'PUBLISHED' THEN
        IF NEW.row_version <> OLD.row_version + 1 THEN
            RAISE EXCEPTION 'project template publish must increment row_version'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.lifecycle_status = 'PUBLISHED' AND NEW.lifecycle_status = 'RETIRED' THEN
        IF NEW.row_version <> OLD.row_version + 1
            OR NEW.published_at IS DISTINCT FROM OLD.published_at
            OR NEW.published_by_actor_type IS DISTINCT FROM OLD.published_by_actor_type
            OR NEW.published_by_user_id IS DISTINCT FROM OLD.published_by_user_id
            OR NEW.published_by_system_code IS DISTINCT FROM OLD.published_by_system_code THEN
            RAISE EXCEPTION 'project template retirement may only append retirement facts'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid project template lifecycle transition'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_project_template_definition_immutable
    BEFORE UPDATE OR DELETE ON yumpoo.project_template_definition
    FOR EACH ROW EXECUTE FUNCTION yumpoo.guard_project_template_definition_mutation();

INSERT INTO yumpoo.project_template_definition (
    id, template_key, template_version, version_code, project_type, display_name,
    lifecycle_status, row_version, created_at, updated_at
) VALUES
    ('20000000-0000-4000-8000-000000000001', 'RND', 1, 'RND_V1',
        'PRODUCT_DEVELOPMENT', '产品研发', 'DRAFT', 0, transaction_timestamp(), transaction_timestamp()),
    ('20000000-0000-4000-8000-000000000002', 'PRE_SALES', 1, 'PRE_SALES_V1',
        'PRE_SALES', '项目售前', 'DRAFT', 0, transaction_timestamp(), transaction_timestamp()),
    ('20000000-0000-4000-8000-000000000003', 'IMPLEMENTATION', 1, 'IMPLEMENTATION_V1',
        'IMPLEMENTATION', '现场实施', 'DRAFT', 0, transaction_timestamp(), transaction_timestamp()),
    ('20000000-0000-4000-8000-000000000004', 'HYPERCARE', 1, 'HYPERCARE_V1',
        'HYPERCARE', '投产陪护', 'DRAFT', 0, transaction_timestamp(), transaction_timestamp());

INSERT INTO yumpoo.project_template_content_blueprint (
    template_id, content_code, display_name, work_item_type, default_view_type, sort_order
)
SELECT template.id, blueprint.content_code, blueprint.display_name,
       blueprint.work_item_type, 'TABLE', blueprint.sort_order
FROM yumpoo.project_template_definition template
CROSS JOIN (VALUES
    ('REQUIREMENTS', '需求', 'REQUIREMENT', 10),
    ('TASKS', '任务', 'TASK', 20),
    ('DEFECTS', '缺陷', 'DEFECT', 30)
) AS blueprint(content_code, display_name, work_item_type, sort_order);

INSERT INTO yumpoo.workflow_status_definition (
    template_id, status_code, display_name, status_category, sort_order, is_initial, is_terminal
) VALUES
    ('20000000-0000-4000-8000-000000000001', 'BACKLOG', '待规划', 'TODO', 10, true, false),
    ('20000000-0000-4000-8000-000000000001', 'READY', '就绪', 'TODO', 20, false, false),
    ('20000000-0000-4000-8000-000000000001', 'IN_PROGRESS', '进行中', 'IN_PROGRESS', 30, false, false),
    ('20000000-0000-4000-8000-000000000001', 'IN_REVIEW', '评审中', 'IN_PROGRESS', 40, false, false),
    ('20000000-0000-4000-8000-000000000001', 'DONE', '已完成', 'DONE', 50, false, true),
    ('20000000-0000-4000-8000-000000000001', 'CANCELED', '已取消', 'CANCELED', 60, false, true),

    ('20000000-0000-4000-8000-000000000002', 'TO_ASSESS', '待评估', 'TODO', 10, true, false),
    ('20000000-0000-4000-8000-000000000002', 'PREPARING', '准备中', 'IN_PROGRESS', 20, false, false),
    ('20000000-0000-4000-8000-000000000002', 'CUSTOMER_REVIEW', '客户评审', 'IN_PROGRESS', 30, false, false),
    ('20000000-0000-4000-8000-000000000002', 'WON', '已赢单', 'DONE', 40, false, true),
    ('20000000-0000-4000-8000-000000000002', 'LOST', '已失单', 'CANCELED', 50, false, true),
    ('20000000-0000-4000-8000-000000000002', 'CANCELED', '已取消', 'CANCELED', 60, false, true),

    ('20000000-0000-4000-8000-000000000003', 'PLANNED', '已计划', 'TODO', 10, true, false),
    ('20000000-0000-4000-8000-000000000003', 'IN_PROGRESS', '进行中', 'IN_PROGRESS', 20, false, false),
    ('20000000-0000-4000-8000-000000000003', 'WAITING_ACCEPTANCE', '待验收', 'IN_PROGRESS', 30, false, false),
    ('20000000-0000-4000-8000-000000000003', 'ACCEPTED', '已验收', 'DONE', 40, false, true),
    ('20000000-0000-4000-8000-000000000003', 'CANCELED', '已取消', 'CANCELED', 50, false, true),

    ('20000000-0000-4000-8000-000000000004', 'OPEN', '待处理', 'TODO', 10, true, false),
    ('20000000-0000-4000-8000-000000000004', 'DIAGNOSING', '诊断中', 'IN_PROGRESS', 20, false, false),
    ('20000000-0000-4000-8000-000000000004', 'FIXING', '修复中', 'IN_PROGRESS', 30, false, false),
    ('20000000-0000-4000-8000-000000000004', 'MONITORING', '观察中', 'IN_PROGRESS', 40, false, false),
    ('20000000-0000-4000-8000-000000000004', 'CLOSED', '已关闭', 'DONE', 50, false, true),
    ('20000000-0000-4000-8000-000000000004', 'CANCELED', '已取消', 'CANCELED', 60, false, true);

INSERT INTO yumpoo.workflow_transition_definition (
    template_id, from_status, to_status, required_permission, requires_resolution
) VALUES
    ('20000000-0000-4000-8000-000000000001', 'BACKLOG', 'READY', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'READY', 'IN_PROGRESS', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'IN_PROGRESS', 'IN_REVIEW', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'IN_REVIEW', 'DONE', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'BACKLOG', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'READY', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'IN_PROGRESS', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000001', 'IN_REVIEW', 'CANCELED', 'MEMBER', false),

    ('20000000-0000-4000-8000-000000000002', 'TO_ASSESS', 'PREPARING', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000002', 'PREPARING', 'CUSTOMER_REVIEW', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000002', 'CUSTOMER_REVIEW', 'WON', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000002', 'CUSTOMER_REVIEW', 'LOST', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000002', 'TO_ASSESS', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000002', 'PREPARING', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000002', 'CUSTOMER_REVIEW', 'CANCELED', 'MEMBER', false),

    ('20000000-0000-4000-8000-000000000003', 'PLANNED', 'IN_PROGRESS', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000003', 'IN_PROGRESS', 'WAITING_ACCEPTANCE', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000003', 'WAITING_ACCEPTANCE', 'ACCEPTED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000003', 'PLANNED', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000003', 'IN_PROGRESS', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000003', 'WAITING_ACCEPTANCE', 'CANCELED', 'MEMBER', false),

    ('20000000-0000-4000-8000-000000000004', 'OPEN', 'DIAGNOSING', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'DIAGNOSING', 'FIXING', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'FIXING', 'MONITORING', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'MONITORING', 'CLOSED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'OPEN', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'DIAGNOSING', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'FIXING', 'CANCELED', 'MEMBER', false),
    ('20000000-0000-4000-8000-000000000004', 'MONITORING', 'CANCELED', 'MEMBER', false);

UPDATE yumpoo.project_template_definition
   SET lifecycle_status = 'PUBLISHED',
       row_version = 1,
       published_at = transaction_timestamp(),
       published_by_actor_type = 'SYSTEM',
       published_by_system_code = 'APPLICATION_RELEASE',
       updated_at = transaction_timestamp();

CREATE INDEX idx_project_template_selectable
    ON yumpoo.project_template_definition (project_type, template_key, template_version DESC)
    WHERE lifecycle_status = 'PUBLISHED';

COMMENT ON TABLE yumpoo.project_template_definition IS
    'Immutable versioned project template catalog owned by templateworkflow.';
COMMENT ON TABLE yumpoo.project_template_content_blueprint IS
    'Initial Content blueprints copied by workitem during atomic Project creation.';
