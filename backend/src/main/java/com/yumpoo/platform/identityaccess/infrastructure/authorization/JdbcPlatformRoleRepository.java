package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import com.yumpoo.platform.identityaccess.application.authorization.PlatformRoleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcPlatformRoleRepository implements PlatformRoleRepository {

    private final JdbcClient jdbcClient;

    public JdbcPlatformRoleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Set<String> findActiveRoleCodes(UUID companyId, UUID userId) {
        return Set.copyOf(new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT role_code
                        FROM yumpoo.platform_role_assignment
                        WHERE company_id = :companyId
                          AND user_id = :userId
                          AND status = 'ACTIVE'
                        ORDER BY CASE role_code
                            WHEN 'COMPANY_ADMIN' THEN 1
                            WHEN 'APP_MANAGER' THEN 2
                            ELSE 3
                        END
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query(String.class)
                .list()));
    }

    @Override
    public Set<UUID> findActiveCompanyAdminIds(UUID companyId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT DISTINCT u.id FROM yumpoo.identity_user u
                JOIN yumpoo.platform_role_assignment a ON a.company_id=u.company_id AND a.user_id=u.id
                WHERE u.company_id=:company AND u.employment_status='ACTIVE' AND u.account_status='ENABLED'
                  AND a.role_code IN ('COMPANY_ADMIN','APP_MANAGER') AND a.status='ACTIVE'
                """).param("company", companyId).query(UUID.class).list());
    }
}
