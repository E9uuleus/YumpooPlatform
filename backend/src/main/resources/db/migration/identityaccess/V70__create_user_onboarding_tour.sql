CREATE TABLE yumpoo.user_onboarding_tour (
    company_id uuid NOT NULL,
    user_id uuid NOT NULL,
    tour_id varchar(48) NOT NULL,
    version integer NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_user_onboarding_tour PRIMARY KEY (company_id, user_id, tour_id),
    CONSTRAINT fk_user_onboarding_tour_user FOREIGN KEY (user_id, company_id)
        REFERENCES yumpoo.identity_user (id, company_id) ON DELETE CASCADE
);

-- 上线前已存在的账号视为老用户：预置全部 v1 引导为已处理，之后不再自动弹出。
INSERT INTO yumpoo.user_onboarding_tour (company_id, user_id, tour_id, version)
SELECT u.company_id, u.id, t.tour_id, 1
FROM yumpoo.identity_user u
CROSS JOIN (VALUES ('welcome'), ('project-work-items'), ('workspace'), ('dashboards'), ('inbox'), ('company')) AS t(tour_id);
