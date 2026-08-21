CREATE INDEX IF NOT EXISTS idx_project_company_lifecycle_name_code
    ON yumpoo.project (company_id, lifecycle, name, project_code, id);

CREATE INDEX IF NOT EXISTS idx_project_membership_company_user_active_project
    ON yumpoo.project_membership (company_id, user_id, project_id)
    WHERE status = 'ACTIVE';
