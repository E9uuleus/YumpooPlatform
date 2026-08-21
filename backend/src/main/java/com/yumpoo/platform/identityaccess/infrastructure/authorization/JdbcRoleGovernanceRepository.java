package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.authorization.GovernanceLifecycleStatus;
import com.yumpoo.platform.identityaccess.application.authorization.GovernanceStateSnapshot;
import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentSnapshot;
import com.yumpoo.platform.identityaccess.application.authorization.RoleAssignmentStatus;
import com.yumpoo.platform.identityaccess.application.authorization.RoleGovernanceRepository;
import com.yumpoo.platform.identityaccess.application.authorization.RoleUserSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcRoleGovernanceRepository implements RoleGovernanceRepository {

    private final JdbcClient jdbcClient;

    public JdbcRoleGovernanceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public GovernanceStateSnapshot lockState(UUID companyId) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.app_manager_governance_state (
                            company_id, lifecycle_status, created_at, updated_at
                        )
                        SELECT id, 'UNINITIALIZED', transaction_timestamp(), transaction_timestamp()
                        FROM yumpoo.company
                        WHERE id = :companyId
                        ON CONFLICT (company_id) DO NOTHING
                        """)
                .param("companyId", companyId)
                .update();
        return jdbcClient.sql("""
                        SELECT company_id, lifecycle_status, event_version, row_version
                        FROM yumpoo.app_manager_governance_state
                        WHERE company_id = :companyId
                        FOR UPDATE
                        """)
                .param("companyId", companyId)
                .query(JdbcRoleGovernanceRepository::mapState)
                .optional()
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    @Override
    public Optional<RoleUserSnapshot> lockUser(UUID companyId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, company_id, employment_status, account_status,
                               authorization_version, row_version
                        FROM yumpoo.identity_user
                        WHERE company_id = :companyId AND id = :userId
                        FOR UPDATE
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query((resultSet, rowNumber) -> mapUser(resultSet))
                .optional();
    }

    @Override
    public Optional<RoleUserSnapshot> findUser(UUID companyId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, company_id, employment_status, account_status,
                               authorization_version, row_version
                        FROM yumpoo.identity_user
                        WHERE company_id = :companyId AND id = :userId
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query((resultSet, rowNumber) -> mapUser(resultSet))
                .optional();
    }

    @Override
    public Optional<RoleUserSnapshot> findAvailableAppManager(UUID companyId) {
        return jdbcClient.sql("""
                        SELECT member.id, member.company_id, member.employment_status,
                               member.account_status, member.authorization_version,
                               member.row_version
                        FROM yumpoo.identity_user member
                        JOIN yumpoo.platform_role_assignment assignment
                          ON assignment.company_id = member.company_id
                         AND assignment.user_id = member.id
                         AND assignment.role_code = 'APP_MANAGER'
                         AND assignment.status = 'ACTIVE'
                        WHERE member.company_id = :companyId
                          AND member.employment_status = 'ACTIVE'
                          AND member.account_status = 'ENABLED'
                        ORDER BY assignment.granted_at, assignment.id
                        LIMIT 1
                        """)
                .param("companyId", companyId)
                .query((resultSet, rowNumber) -> mapUser(resultSet))
                .optional();
    }

    @Override
    public Optional<RoleAssignmentSnapshot> lockAssignment(
            UUID companyId, UUID assignmentId, ManagedPlatformRole expectedRole
    ) {
        return jdbcClient.sql("""
                        SELECT id, company_id, user_id, role_code, status, row_version, granted_at
                        FROM yumpoo.platform_role_assignment
                        WHERE company_id = :companyId AND id = :assignmentId
                          AND role_code = :roleCode
                        FOR UPDATE
                        """)
                .param("companyId", companyId)
                .param("assignmentId", assignmentId)
                .param("roleCode", expectedRole.name())
                .query(JdbcRoleGovernanceRepository::mapAssignment)
                .optional();
    }

    @Override
    public Optional<RoleAssignmentSnapshot> findActiveAssignment(
            UUID companyId, UUID userId, ManagedPlatformRole role
    ) {
        return jdbcClient.sql("""
                        SELECT id, company_id, user_id, role_code, status, row_version, granted_at
                        FROM yumpoo.platform_role_assignment
                        WHERE company_id = :companyId
                          AND user_id = :userId
                          AND role_code = :roleCode
                          AND status = 'ACTIVE'
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .param("roleCode", role.name())
                .query(JdbcRoleGovernanceRepository::mapAssignment)
                .optional();
    }

    @Override
    public boolean hasAppManagerHistory(UUID companyId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM yumpoo.platform_role_assignment
                            WHERE company_id = :companyId AND role_code = 'APP_MANAGER'
                        )
                        """)
                .param("companyId", companyId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean hasAnyRoleHistory(UUID companyId) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM yumpoo.platform_role_assignment
                            WHERE company_id = :companyId
                        )
                        """)
                .param("companyId", companyId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public int countAvailableAppManagers(UUID companyId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.platform_role_assignment assignment
                        JOIN yumpoo.identity_user member
                          ON member.id = assignment.user_id
                         AND member.company_id = assignment.company_id
                        WHERE assignment.company_id = :companyId
                          AND assignment.role_code = 'APP_MANAGER'
                          AND assignment.status = 'ACTIVE'
                          AND member.employment_status = 'ACTIVE'
                          AND member.account_status = 'ENABLED'
                        """)
                .param("companyId", companyId)
                .query(Integer.class)
                .single();
    }

    @Override
    public RoleAssignmentSnapshot grant(
            UUID assignmentId, UUID companyId, UUID userId, ManagedPlatformRole role,
            String actorType, UUID actorUserId, String systemCode,
            String reasonReference, Instant now
    ) {
        OffsetDateTime databaseNow = databaseTime(now);
        return jdbcClient.sql("""
                        INSERT INTO yumpoo.platform_role_assignment (
                            id, company_id, user_id, role_code, scope_type, scope_id, status,
                            granted_by_actor_type, granted_by_user_id, granted_by_system_code,
                            grant_reason, granted_at, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, :roleCode, :scopeType, :companyId, 'ACTIVE',
                            :actorType, :actorUserId, :systemCode,
                            :reason, :now, :now, :now
                        )
                        RETURNING id, company_id, user_id, role_code, status, row_version, granted_at
                        """)
                .param("id", assignmentId)
                .param("companyId", companyId)
                .param("userId", userId)
                .param("roleCode", role.name())
                .param("scopeType", role.scopeType())
                .param("actorType", actorType)
                .param("actorUserId", actorUserId)
                .param("systemCode", systemCode)
                .param("reason", reasonReference)
                .param("now", databaseNow)
                .query(JdbcRoleGovernanceRepository::mapAssignment)
                .single();
    }

    @Override
    public RoleAssignmentSnapshot revoke(
            RoleAssignmentSnapshot assignment, UUID actorUserId,
            String reasonReference, Instant now
    ) {
        OffsetDateTime databaseNow = databaseTime(now);
        List<RoleAssignmentSnapshot> changed = jdbcClient.sql("""
                        UPDATE yumpoo.platform_role_assignment
                        SET status = 'REVOKED',
                            revoked_by_user_id = :actorUserId,
                            revoked_at = :now,
                            revoke_reason = :reason,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE id = :assignmentId
                          AND company_id = :companyId
                          AND status = 'ACTIVE'
                          AND row_version = :expectedVersion
                        RETURNING id, company_id, user_id, role_code, status, row_version, granted_at
                        """)
                .param("actorUserId", actorUserId)
                .param("now", databaseNow)
                .param("reason", reasonReference)
                .param("assignmentId", assignment.assignmentId())
                .param("companyId", assignment.companyId())
                .param("expectedVersion", assignment.rowVersion())
                .query(JdbcRoleGovernanceRepository::mapAssignment)
                .list();
        if (changed.size() != 1) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        return changed.getFirst();
    }

    @Override
    public RoleUserSnapshot incrementAuthorizationVersion(
            UUID companyId, UUID userId, long expectedRowVersion
    ) {
        List<RoleUserSnapshot> changed = jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE company_id = :companyId
                          AND id = :userId
                          AND row_version = :expectedVersion
                        RETURNING id, company_id, employment_status, account_status,
                                  authorization_version, row_version
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .param("expectedVersion", expectedRowVersion)
                .query((resultSet, rowNumber) -> mapUser(resultSet))
                .list();
        if (changed.size() != 1) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        return changed.getFirst();
    }

    @Override
    public GovernanceStateSnapshot markAvailable(UUID companyId, boolean initialize, Instant now) {
        OffsetDateTime databaseNow = databaseTime(now);
        return jdbcClient.sql("""
                        UPDATE yumpoo.app_manager_governance_state
                        SET lifecycle_status = 'AVAILABLE',
                            initialized_at = CASE
                                WHEN :initialize THEN :now ELSE initialized_at
                            END,
                            missing_since = NULL,
                            event_version = event_version + 1,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE company_id = :companyId
                        RETURNING company_id, lifecycle_status, event_version, row_version
                        """)
                .param("initialize", initialize)
                .param("now", databaseNow)
                .param("companyId", companyId)
                .query(JdbcRoleGovernanceRepository::mapState)
                .single();
    }

    @Override
    public GovernanceStateSnapshot markMissing(UUID companyId, Instant now) {
        OffsetDateTime databaseNow = databaseTime(now);
        return jdbcClient.sql("""
                        UPDATE yumpoo.app_manager_governance_state
                        SET lifecycle_status = 'MISSING',
                            missing_since = :now,
                            event_version = event_version + 1,
                            row_version = row_version + 1,
                            updated_at = :now
                        WHERE company_id = :companyId
                          AND lifecycle_status <> 'MISSING'
                        RETURNING company_id, lifecycle_status, event_version, row_version
                        """)
                .param("now", databaseNow)
                .param("companyId", companyId)
                .query(JdbcRoleGovernanceRepository::mapState)
                .optional()
                .orElseGet(() -> lockState(companyId));
    }

    private RoleUserSnapshot mapUser(ResultSet resultSet) throws SQLException {
        UUID companyId = resultSet.getObject("company_id", UUID.class);
        UUID userId = resultSet.getObject("id", UUID.class);
        Set<ManagedPlatformRole> roles = new LinkedHashSet<>();
        jdbcClient.sql("""
                        SELECT role_code FROM yumpoo.platform_role_assignment
                        WHERE company_id = :companyId AND user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("companyId", companyId)
                .param("userId", userId)
                .query(String.class)
                .list()
                .forEach(role -> roles.add(ManagedPlatformRole.valueOf(role)));
        return new RoleUserSnapshot(
                userId,
                companyId,
                resultSet.getString("employment_status"),
                resultSet.getString("account_status"),
                resultSet.getLong("authorization_version"),
                resultSet.getLong("row_version"),
                Set.copyOf(roles)
        );
    }

    private static RoleAssignmentSnapshot mapAssignment(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new RoleAssignmentSnapshot(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                ManagedPlatformRole.valueOf(resultSet.getString("role_code")),
                RoleAssignmentStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("row_version"),
                resultSet.getObject("granted_at", OffsetDateTime.class).toInstant()
        );
    }

    private static GovernanceStateSnapshot mapState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new GovernanceStateSnapshot(
                resultSet.getObject("company_id", UUID.class),
                GovernanceLifecycleStatus.valueOf(resultSet.getString("lifecycle_status")),
                resultSet.getLong("event_version"),
                resultSet.getLong("row_version")
        );
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
