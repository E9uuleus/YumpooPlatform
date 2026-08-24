ALTER TABLE yumpoo.workspace
    ALTER COLUMN created_by_user_id DROP NOT NULL,
    ALTER COLUMN updated_by_user_id DROP NOT NULL;

CREATE TEMP TABLE main_workspace_selection (
    company_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL
) ON COMMIT DROP;

INSERT INTO main_workspace_selection (company_id, workspace_id)
SELECT company_id, id
FROM (
    SELECT w.company_id,
           w.id,
           row_number() OVER (
               PARTITION BY w.company_id
               ORDER BY CASE
                            WHEN w.code = 'MAIN' THEN 0
                            WHEN w.status = 'ACTIVE' THEN 1
                            ELSE 2
                        END,
                        w.sort_order,
                        w.created_at,
                        w.id
           ) AS priority
    FROM yumpoo.workspace w
) ranked
WHERE priority = 1;

INSERT INTO yumpoo.workspace (
    id, company_id, code, name, description, sort_order, status, row_version,
    created_at, created_by_user_id, updated_at, updated_by_user_id
)
SELECT (
           substr(md5(c.id::text || ':MAIN'), 1, 8) || '-' ||
           substr(md5(c.id::text || ':MAIN'), 9, 4) || '-4' ||
           substr(md5(c.id::text || ':MAIN'), 14, 3) || '-a' ||
           substr(md5(c.id::text || ':MAIN'), 18, 3) || '-' ||
           substr(md5(c.id::text || ':MAIN'), 21, 12)
       )::uuid,
       c.id,
       'MAIN',
       '主工作空间',
       NULL,
       0,
       'ACTIVE',
       0,
       CURRENT_TIMESTAMP,
       NULL,
       CURRENT_TIMESTAMP,
       NULL
FROM yumpoo.company c
WHERE NOT EXISTS (
    SELECT 1 FROM main_workspace_selection selected WHERE selected.company_id = c.id
);

INSERT INTO main_workspace_selection (company_id, workspace_id)
SELECT w.company_id, w.id
FROM yumpoo.workspace w
WHERE w.code = 'MAIN'
  AND NOT EXISTS (
      SELECT 1 FROM main_workspace_selection selected WHERE selected.company_id = w.company_id
  );

UPDATE yumpoo.project p
SET workspace_id = selected.workspace_id
FROM main_workspace_selection selected
WHERE p.company_id = selected.company_id
  AND p.workspace_id <> selected.workspace_id;

DELETE FROM yumpoo.workspace w
USING main_workspace_selection selected
WHERE w.company_id = selected.company_id
  AND w.id <> selected.workspace_id;

UPDATE yumpoo.workspace w
SET code = 'MAIN',
    sort_order = 0,
    status = 'ACTIVE'
FROM main_workspace_selection selected
WHERE w.company_id = selected.company_id
  AND w.id = selected.workspace_id;

DROP INDEX IF EXISTS yumpoo.idx_workspace_company_status_navigation;

ALTER TABLE yumpoo.workspace
    ADD CONSTRAINT uq_workspace_company_singleton UNIQUE (company_id),
    ADD CONSTRAINT ck_workspace_main_code CHECK (code = 'MAIN'),
    ADD CONSTRAINT ck_workspace_main_sort_order CHECK (sort_order = 0),
    ADD CONSTRAINT ck_workspace_main_status CHECK (status = 'ACTIVE');

SET CONSTRAINTS ALL IMMEDIATE;

CREATE INDEX idx_project_company_updated_at
    ON yumpoo.project (company_id, updated_at DESC, id);

CREATE OR REPLACE FUNCTION yumpoo.provision_main_workspace()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    digest text := md5(NEW.id::text || ':MAIN');
    main_id uuid;
BEGIN
    main_id := (
        substr(digest, 1, 8) || '-' || substr(digest, 9, 4) || '-4' ||
        substr(digest, 14, 3) || '-a' || substr(digest, 18, 3) || '-' ||
        substr(digest, 21, 12)
    )::uuid;
    INSERT INTO yumpoo.workspace (
        id, company_id, code, name, description, sort_order, status, row_version,
        created_at, created_by_user_id, updated_at, updated_by_user_id
    ) VALUES (
        main_id, NEW.id, 'MAIN', '主工作空间', NULL, 0, 'ACTIVE', 0,
        CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP, NULL
    ) ON CONFLICT (company_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_company_provision_main_workspace
    AFTER INSERT ON yumpoo.company
    FOR EACH ROW EXECUTE FUNCTION yumpoo.provision_main_workspace();

CREATE OR REPLACE FUNCTION yumpoo.require_company_main_workspace()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM yumpoo.company WHERE id = OLD.company_id)
       AND NOT EXISTS (SELECT 1 FROM yumpoo.workspace WHERE company_id = OLD.company_id) THEN
        RAISE EXCEPTION 'company % must retain its MAIN workspace', OLD.company_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_workspace_require_company_main
    AFTER DELETE ON yumpoo.workspace
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION yumpoo.require_company_main_workspace();

COMMENT ON TABLE yumpoo.workspace IS
    'Company-scoped singleton MAIN workspace owned by catalog; internal grouping only and never an authorization boundary.';
