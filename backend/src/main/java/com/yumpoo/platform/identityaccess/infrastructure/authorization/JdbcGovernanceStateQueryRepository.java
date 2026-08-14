package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import com.yumpoo.platform.identityaccess.application.authorization.GovernanceMemberState;
import com.yumpoo.platform.identityaccess.application.authorization.GovernanceStateQueryRepository;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentSnapshot;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcGovernanceStateQueryRepository implements GovernanceStateQueryRepository {

    private final JdbcClient jdbcClient;

    public JdbcGovernanceStateQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<GovernanceMemberState> findMember(UUID companyId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, display_name, employment_status, account_status,
                               authorization_version, row_version
                        FROM yumpoo.identity_user
                        WHERE company_id = :companyId AND id = :userId
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query((rs, row) -> new GovernanceMemberState(
                        rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("employment_status"), rs.getString("account_status"),
                        roles(companyId, userId), rs.getLong("authorization_version"),
                        rs.getLong("row_version")))
                .optional();
    }

    @Override
    public Optional<RoleAssignmentSnapshot> findAssignment(
            UUID companyId, UUID assignmentId, ManagedPlatformRole expectedRole
    ) {
        return jdbcClient.sql("""
                        SELECT id, company_id, user_id, role_code, status, row_version, granted_at
                        FROM yumpoo.platform_role_assignment
                        WHERE company_id = :companyId AND id = :assignmentId
                          AND role_code = :roleCode
                        """)
                .param("companyId", companyId)
                .param("assignmentId", assignmentId)
                .param("roleCode", expectedRole.name())
                .query(JdbcGovernanceStateQueryRepository::assignment)
                .optional();
    }

    private Set<ManagedPlatformRole> roles(UUID companyId, UUID userId) {
        Set<ManagedPlatformRole> roles = new LinkedHashSet<>();
        jdbcClient.sql("""
                        SELECT role_code FROM yumpoo.platform_role_assignment
                        WHERE company_id = :companyId AND user_id = :userId AND status = 'ACTIVE'
                        ORDER BY role_code
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query(String.class)
                .list()
                .forEach(code -> roles.add(ManagedPlatformRole.valueOf(code)));
        return Set.copyOf(roles);
    }

    private static RoleAssignmentSnapshot assignment(ResultSet rs, int row) throws SQLException {
        return new RoleAssignmentSnapshot(
                rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                ManagedPlatformRole.valueOf(rs.getString("role_code")),
                RoleAssignmentStatus.valueOf(rs.getString("status")),
                rs.getLong("row_version"), rs.getTimestamp("granted_at").toInstant());
    }
}
