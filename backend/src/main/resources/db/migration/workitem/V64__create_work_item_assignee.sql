CREATE TABLE yumpoo.work_item_assignee (
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    work_item_id uuid NOT NULL,
    user_id uuid NOT NULL,
    position smallint NOT NULL,
    PRIMARY KEY (work_item_id, user_id),
    CONSTRAINT uq_work_item_assignee_position UNIQUE (work_item_id, position),
    CONSTRAINT fk_work_item_assignee_item_scope FOREIGN KEY (work_item_id, company_id, project_id)
        REFERENCES yumpoo.work_item (id, company_id, project_id) ON DELETE CASCADE,
    CONSTRAINT fk_work_item_assignee_user_company FOREIGN KEY (user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id),
    CONSTRAINT ck_work_item_assignee_position CHECK (position BETWEEN 0 AND 19));
CREATE INDEX idx_work_item_assignee_user ON yumpoo.work_item_assignee (company_id, user_id, work_item_id);
INSERT INTO yumpoo.work_item_assignee (company_id, project_id, work_item_id, user_id, position)
SELECT company_id, project_id, id, assignee_user_id, 0 FROM yumpoo.work_item WHERE assignee_user_id IS NOT NULL;
COMMENT ON TABLE yumpoo.work_item_assignee IS 'Work item assignee set (0..20 equal owners); position 0 mirrors work_item.assignee_user_id.';
