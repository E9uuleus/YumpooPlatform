package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberBinding;
import com.yumpoo.platform.identityaccess.application.directory.DirectoryMemberProvisioningRepository;
import com.yumpoo.platform.identityaccess.application.directory.WeComMemberProfile;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentity;
import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentityProvider;
import com.yumpoo.platform.identityaccess.domain.identity.ProfileHash;
import com.yumpoo.platform.identityaccess.domain.identity.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDirectoryMemberProvisioningRepository
        implements DirectoryMemberProvisioningRepository {

    private static final String SELECT_BINDING = """
            SELECT
                identity_user.id AS user_id,
                identity_user.company_id,
                identity_user.employment_status,
                identity_user.account_status,
                identity_user.display_name,
                identity_user.email,
                identity_user.mobile,
                identity_user.department_summary,
                identity_user.directory_synced_at,
                identity_user.left_at,
                identity_user.left_reason,
                identity_user.account_disabled_at,
                identity_user.account_disabled_by_user_id,
                identity_user.account_disabled_reason,
                identity_user.authorization_version,
                identity_user.row_version,
                identity_user.created_at AS user_created_at,
                identity_user.updated_at AS user_updated_at,
                external_identity.id AS external_identity_id,
                external_identity.provider,
                external_identity.external_user_id,
                external_identity.provider_employment_status,
                external_identity.raw_profile_hash,
                external_identity.last_seen_at,
                external_identity.created_at AS identity_created_at,
                external_identity.updated_at AS identity_updated_at
            FROM yumpoo.external_identity external_identity
            JOIN yumpoo.identity_user identity_user
              ON identity_user.id = external_identity.user_id
             AND identity_user.company_id = external_identity.company_id
            WHERE external_identity.company_id = :companyId
              AND external_identity.provider = :provider
              AND external_identity.external_user_id = :externalUserId
            """;

    private final JdbcClient jdbcClient;

    public JdbcDirectoryMemberProvisioningRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public void acquireProvisionLock(
            UUID companyId,
            ExternalIdentityProvider provider,
            String externalUserId
    ) {
        int[] keys = advisoryLockKeys(companyId, provider, externalUserId);
        jdbcClient.sql("SELECT pg_advisory_xact_lock(:namespaceKey, :identityKey)")
                .param("namespaceKey", keys[0])
                .param("identityKey", keys[1])
                .query((resultSet, rowNumber) -> Boolean.TRUE)
                .single();
    }

    @Override
    public Optional<DirectoryMemberBinding> findByExternalIdentity(
            UUID companyId,
            ExternalIdentityProvider provider,
            String externalUserId
    ) {
        List<DirectoryMemberBinding> bindings = jdbcClient.sql(SELECT_BINDING)
                .param("companyId", companyId)
                .param("provider", provider.name())
                .param("externalUserId", externalUserId)
                .query(JdbcDirectoryMemberProvisioningRepository::mapBinding)
                .list();
        if (bindings.size() > 1) {
            throw new IllegalStateException("External identity uniqueness was violated");
        }
        return bindings.stream().findFirst();
    }

    @Override
    public DirectoryMemberBinding create(
            UUID companyId,
            WeComMemberProfile profile,
            Instant now
    ) {
        UUID userId = UUID.randomUUID();
        UUID externalIdentityId = UUID.randomUUID();
        OffsetDateTime databaseNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        int insertedUsers = jdbcClient.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status,
                            display_name, email, mobile, department_summary,
                            directory_synced_at, row_version, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, 'ACTIVE', 'ENABLED',
                            :displayName, :email, :mobile, :departmentSummary,
                            :now, 0, :now, :now
                        )
                        """)
                .param("id", userId)
                .param("companyId", companyId)
                .param("displayName", profile.displayName())
                .param("email", profile.email().applyTo(null))
                .param("mobile", profile.mobile().applyTo(null))
                .param("departmentSummary", profile.departmentSummary())
                .param("now", databaseNow)
                .update();
        requireOne(insertedUsers, "create User");

        int insertedIdentities = jdbcClient.sql("""
                        INSERT INTO yumpoo.external_identity (
                            id, company_id, user_id, provider, external_user_id,
                            provider_employment_status, raw_profile_hash,
                            last_seen_at, created_at, updated_at
                        ) VALUES (
                            :id, :companyId, :userId, 'WECOM', :externalUserId,
                            'ACTIVE', :rawProfileHash, :now, :now, :now
                        )
                        """)
                .param("id", externalIdentityId)
                .param("companyId", companyId)
                .param("userId", userId)
                .param("externalUserId", profile.externalUserId())
                .param("rawProfileHash", profile.rawProfileHash().value())
                .param("now", databaseNow)
                .update();
        requireOne(insertedIdentities, "create ExternalIdentity");

        return new DirectoryMemberBinding(
                new User(
                        userId,
                        companyId,
                        EmploymentStatus.ACTIVE,
                        AccountStatus.ENABLED,
                        profile.displayName(),
                        profile.email().applyTo(null),
                        profile.mobile().applyTo(null),
                        profile.departmentSummary(),
                        now,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        now,
                        now
                ),
                new ExternalIdentity(
                        externalIdentityId,
                        companyId,
                        userId,
                        ExternalIdentityProvider.WECOM,
                        profile.externalUserId(),
                        EmploymentStatus.ACTIVE,
                        profile.rawProfileHash(),
                        now,
                        now,
                        now
                )
        );
    }

    @Override
    public DirectoryMemberBinding refresh(
            DirectoryMemberBinding current,
            WeComMemberProfile profile,
            Instant now
    ) {
        User user = current.user();
        boolean returned = user.employmentStatus() == EmploymentStatus.LEFT
                || current.externalIdentity().providerEmploymentStatus() == EmploymentStatus.LEFT;
        OffsetDateTime databaseNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        int updatedUsers = jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET employment_status = 'ACTIVE',
                            display_name = :displayName,
                            email = :email,
                            mobile = :mobile,
                            department_summary = :departmentSummary,
                            directory_synced_at = :now,
                            authorization_version = authorization_version + :authorizationIncrement,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :id
                          AND company_id = :companyId
                          AND row_version = :rowVersion
                        """)
                .param("displayName", profile.displayName())
                .param("email", profile.email().applyTo(user.email()))
                .param("mobile", profile.mobile().applyTo(user.mobile()))
                .param("departmentSummary", profile.departmentSummary())
                .param("authorizationIncrement", returned ? 1 : 0)
                .param("now", databaseNow)
                .param("id", user.id())
                .param("companyId", user.companyId())
                .param("rowVersion", user.rowVersion())
                .update();
        requireOne(updatedUsers, "refresh User");

        ExternalIdentity identity = current.externalIdentity();
        int updatedIdentities = jdbcClient.sql("""
                        UPDATE yumpoo.external_identity
                        SET provider_employment_status = 'ACTIVE',
                            raw_profile_hash = :rawProfileHash,
                            last_seen_at = :now,
                            updated_at = :now
                        WHERE id = :id
                          AND company_id = :companyId
                          AND user_id = :userId
                        """)
                .param("rawProfileHash", profile.rawProfileHash().value())
                .param("now", databaseNow)
                .param("id", identity.id())
                .param("companyId", identity.companyId())
                .param("userId", identity.userId())
                .update();
        requireOne(updatedIdentities, "refresh ExternalIdentity");

        return new DirectoryMemberBinding(
                new User(
                        user.id(),
                        user.companyId(),
                        EmploymentStatus.ACTIVE,
                        user.accountStatus(),
                        profile.displayName(),
                        profile.email().applyTo(user.email()),
                        profile.mobile().applyTo(user.mobile()),
                        profile.departmentSummary(),
                        now,
                        user.leftAt(),
                        user.leftReason(),
                        user.accountDisabledAt(),
                        user.accountDisabledByUserId(),
                        user.accountDisabledReason(),
                        user.authorizationVersion() + (returned ? 1 : 0),
                        user.rowVersion() + 1,
                        user.createdAt(),
                        now
                ),
                new ExternalIdentity(
                        identity.id(),
                        identity.companyId(),
                        identity.userId(),
                        identity.provider(),
                        identity.externalUserId(),
                        EmploymentStatus.ACTIVE,
                        profile.rawProfileHash(),
                        now,
                        identity.createdAt(),
                        now
                )
        );
    }

    private static DirectoryMemberBinding mapBinding(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID companyId = resultSet.getObject("company_id", UUID.class);
        UUID userId = resultSet.getObject("user_id", UUID.class);
        User user = new User(
                userId,
                companyId,
                EmploymentStatus.valueOf(resultSet.getString("employment_status")),
                AccountStatus.valueOf(resultSet.getString("account_status")),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getString("mobile"),
                resultSet.getString("department_summary"),
                instant(resultSet, "directory_synced_at"),
                nullableInstant(resultSet, "left_at"),
                resultSet.getString("left_reason"),
                nullableInstant(resultSet, "account_disabled_at"),
                resultSet.getObject("account_disabled_by_user_id", UUID.class),
                resultSet.getString("account_disabled_reason"),
                resultSet.getLong("authorization_version"),
                resultSet.getLong("row_version"),
                instant(resultSet, "user_created_at"),
                instant(resultSet, "user_updated_at")
        );
        ExternalIdentity identity = new ExternalIdentity(
                resultSet.getObject("external_identity_id", UUID.class),
                companyId,
                userId,
                ExternalIdentityProvider.valueOf(resultSet.getString("provider")),
                resultSet.getString("external_user_id"),
                EmploymentStatus.valueOf(resultSet.getString("provider_employment_status")),
                new ProfileHash(resultSet.getString("raw_profile_hash")),
                instant(resultSet, "last_seen_at"),
                instant(resultSet, "identity_created_at"),
                instant(resultSet, "identity_updated_at")
        );
        return new DirectoryMemberBinding(user, identity);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static int[] advisoryLockKeys(
            UUID companyId,
            ExternalIdentityProvider provider,
            String externalUserId
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(externalUserId, "externalUserId must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (companyId + "\0" + provider.name() + "\0" + externalUserId)
                            .getBytes(StandardCharsets.UTF_8)
            );
            ByteBuffer keys = ByteBuffer.wrap(hash);
            return new int[]{keys.getInt(), keys.getInt()};
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void requireOne(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(operation + " did not affect exactly one row");
        }
    }
}
