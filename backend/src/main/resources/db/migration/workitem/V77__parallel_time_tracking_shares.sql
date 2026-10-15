DROP INDEX yumpoo.uq_work_item_running_timer;
CREATE UNIQUE INDEX uq_work_item_running_timer_item
    ON yumpoo.work_item_time_session (company_id, user_id, work_item_id)
    WHERE stopped_at IS NULL AND deleted_at IS NULL;

CREATE TABLE yumpoo.work_item_time_share (
    session_id uuid NOT NULL REFERENCES yumpoo.work_item_time_session (id) ON DELETE CASCADE,
    started_at timestamptz NOT NULL,
    stopped_at timestamptz,
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_item_id uuid NOT NULL,
    concurrency integer NOT NULL CHECK (concurrency >= 1),
    PRIMARY KEY (session_id, started_at)
);
CREATE INDEX ix_work_item_time_share_user ON yumpoo.work_item_time_share (company_id, user_id, started_at);
CREATE INDEX ix_work_item_time_share_item ON yumpoo.work_item_time_share (company_id, work_item_id);
CREATE INDEX ix_work_item_time_share_project ON yumpoo.work_item_time_share (company_id, project_id, started_at);

INSERT INTO yumpoo.work_item_time_share (session_id, started_at, stopped_at, company_id, user_id, project_id, work_item_id, concurrency)
SELECT id, started_at, stopped_at, company_id, user_id, project_id, work_item_id, 1
FROM yumpoo.work_item_time_session
WHERE deleted_at IS NULL AND (stopped_at IS NULL OR stopped_at > started_at);
