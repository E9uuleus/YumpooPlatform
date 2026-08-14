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
}
