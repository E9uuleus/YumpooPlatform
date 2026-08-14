CREATE TABLE yumpoo.app_manager_governance_state (
    company_id uuid PRIMARY KEY,
    lifecycle_status varchar(20) NOT NULL,
    initialized_at timestamptz,
    missing_since timestamptz,
    event_version bigint NOT NULL DEFAULT 0,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT fk_app_manager_governance_state_company
        FOREIGN KEY (company_id) REFERENCES yumpoo.company (id) ON DELETE CASCADE,
    CONSTRAINT ck_app_manager_governance_state_status CHECK (
        lifecycle_status IN ('UNINITIALIZED', 'AVAILABLE', 'MISSING')
    ),
    CONSTRAINT ck_app_manager_governance_state_lifecycle CHECK (
        (lifecycle_status = 'UNINITIALIZED'
            AND initialized_at IS NULL AND missing_since IS NULL)
        OR (lifecycle_status = 'AVAILABLE'
            AND initialized_at IS NOT NULL AND missing_since IS NULL)
        OR (lifecycle_status = 'MISSING'
            AND initialized_at IS NOT NULL AND missing_since IS NOT NULL)
    ),
    CONSTRAINT ck_app_manager_governance_state_versions CHECK (
        event_version >= 0 AND row_version >= 0
    ),
    CONSTRAINT ck_app_manager_governance_state_timestamps CHECK (
        updated_at >= created_at
        AND (initialized_at IS NULL OR initialized_at BETWEEN created_at AND updated_at)
        AND (missing_since IS NULL OR missing_since BETWEEN initialized_at AND updated_at)
    )
);

INSERT INTO yumpoo.app_manager_governance_state (
    company_id,
    lifecycle_status,
    initialized_at,
    missing_since,
    created_at,
    updated_at
)
SELECT company.id,
       CASE
           WHEN history.company_id IS NULL THEN 'UNINITIALIZED'
           WHEN COALESCE(available.available_count, 0) > 0 THEN 'AVAILABLE'
           ELSE 'MISSING'
       END,
       CASE WHEN history.company_id IS NULL THEN NULL ELSE transaction_timestamp() END,
       CASE
           WHEN history.company_id IS NOT NULL
               AND COALESCE(available.available_count, 0) = 0
               THEN transaction_timestamp()
           ELSE NULL
       END,
       transaction_timestamp(),
       transaction_timestamp()
FROM yumpoo.company company
LEFT JOIN (
    SELECT DISTINCT company_id
    FROM yumpoo.platform_role_assignment
    WHERE role_code = 'APP_MANAGER'
) history ON history.company_id = company.id
LEFT JOIN (
    SELECT assignment.company_id, count(*) AS available_count
    FROM yumpoo.platform_role_assignment assignment
    JOIN yumpoo.identity_user member
      ON member.id = assignment.user_id
     AND member.company_id = assignment.company_id
    WHERE assignment.role_code = 'APP_MANAGER'
      AND assignment.status = 'ACTIVE'
      AND member.employment_status = 'ACTIVE'
      AND member.account_status = 'ENABLED'
    GROUP BY assignment.company_id
) available ON available.company_id = company.id;

COMMENT ON TABLE yumpoo.app_manager_governance_state IS
    'Company mutex and durable APP_MANAGER bootstrap/availability state; authorization remains fact-based.';
