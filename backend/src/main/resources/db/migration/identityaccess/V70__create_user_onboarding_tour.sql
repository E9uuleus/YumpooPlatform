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

-- 只回填有成功登录证据的账号；通讯录预同步但从未登录的成员仍需首次引导。
-- 会话定期清理后，成功登录审计仍可证明该账号曾使用平台。
INSERT INTO yumpoo.user_onboarding_tour (company_id, user_id, tour_id, version)
SELECT u.company_id, u.id, t.tour_id, 1
FROM yumpoo.identity_user u
CROSS JOIN (VALUES ('welcome'), ('project-work-items'), ('workspace'), ('dashboards'), ('inbox'), ('company')) AS t(tour_id)
WHERE EXISTS (
    SELECT 1 FROM yumpoo.login_session s
    WHERE s.company_id = u.company_id AND s.user_id = u.id
)
OR EXISTS (
    SELECT 1 FROM yumpoo.security_audit_event a
    WHERE a.company_id = u.company_id AND a.actor_user_id = u.id
        AND a.action = 'LOGIN_SUCCEEDED' AND a.outcome = 'SUCCEEDED'
);
