ALTER TABLE yumpoo.project
    ADD COLUMN deletion_requested_at timestamptz,
    ADD COLUMN deletion_requested_by uuid,
    ADD COLUMN purge_after timestamptz,
    ADD COLUMN deletion_reminder_sent_at timestamptz,
    ADD COLUMN purge_started_at timestamptz,
    ADD CONSTRAINT fk_project_deletion_requester FOREIGN KEY (deletion_requested_by, company_id)
        REFERENCES yumpoo.identity_user(id, company_id),
    ADD CONSTRAINT ck_project_deletion CHECK (
        (deletion_requested_at IS NULL AND deletion_requested_by IS NULL AND purge_after IS NULL
            AND deletion_reminder_sent_at IS NULL AND purge_started_at IS NULL)
        OR (lifecycle='ARCHIVED' AND deletion_requested_at IS NOT NULL
            AND deletion_requested_by IS NOT NULL AND purge_after IS NOT NULL
            AND purge_after > deletion_requested_at
            AND (deletion_reminder_sent_at IS NULL OR deletion_reminder_sent_at >= deletion_requested_at)
            AND (purge_started_at IS NULL OR purge_started_at >= purge_after))
    );

CREATE INDEX idx_project_deletion_due ON yumpoo.project(purge_after,id)
    WHERE purge_after IS NOT NULL AND purge_started_at IS NULL;

CREATE TABLE yumpoo.project_purge_run (
    project_id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    stage varchar(32) NOT NULL,
    cursor_value varchar(320),
    counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    lease_owner varchar(160),
    lease_token uuid,
    lease_until timestamptz,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_project_purge_stage CHECK (stage IN (
        'NOTIFICATION','AUDIT','FILESTORAGE','OUTBOX','WORKITEM','REPORTING','CATALOG','COMPLETED')),
    CONSTRAINT ck_project_purge_counts CHECK (jsonb_typeof(counts)='object'),
    CONSTRAINT ck_project_purge_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_project_purge_completion CHECK (
        (stage='COMPLETED' AND completed_at IS NOT NULL AND lease_owner IS NULL)
        OR (stage<>'COMPLETED' AND completed_at IS NULL))
);
CREATE INDEX idx_project_purge_pending ON yumpoo.project_purge_run(lease_until,started_at,project_id)
    WHERE completed_at IS NULL;

COMMENT ON TABLE yumpoo.project_purge_run IS
    'Resumable project purge stages and counts; deliberately retains only IDs after project deletion.';

CREATE TABLE yumpoo.attachment_project_purge (
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    started_at timestamptz NOT NULL,
    PRIMARY KEY (company_id,project_id)
);

CREATE OR REPLACE FUNCTION yumpoo.assert_project_active_owner_membership()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_project_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'project' THEN
        target_project_id := COALESCE(NEW.id,OLD.id);
    ELSE
        target_project_id := COALESCE(NEW.project_id,OLD.project_id);
    END IF;
    IF EXISTS (SELECT 1 FROM yumpoo.project WHERE id=target_project_id AND purge_started_at IS NULL)
       AND NOT EXISTS (SELECT 1 FROM yumpoo.project p JOIN yumpoo.project_membership m
           ON m.project_id=p.id AND m.user_id=p.owner_user_id AND m.status='ACTIVE' WHERE p.id=target_project_id)
    THEN
        RAISE EXCEPTION 'project owner must have an active membership' USING ERRCODE='check_violation';
    END IF;
    RETURN NULL;
END;
$$;
