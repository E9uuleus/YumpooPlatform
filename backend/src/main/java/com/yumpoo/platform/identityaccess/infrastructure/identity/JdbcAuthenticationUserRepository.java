package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationUser;
import com.yumpoo.platform.identityaccess.application.authentication.AuthenticationUserRepository;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAuthenticationUserRepository implements AuthenticationUserRepository {

    private static final String COLUMNS = """
            user_record.id AS user_id,
            user_record.company_id,
            user_record.display_name,
            user_record.employment_status,
            user_record.account_status,
            identity.provider_employment_status,
            user_record.authorization_version,
            user_record.row_version
            """;

    private final JdbcClient jdbcClient;

    public JdbcAuthenticationUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<AuthenticationUser> lockByWeComIdentity(
            UUID companyId,
            String externalUserId
    ) {
        List<AuthenticationUser> users = jdbcClient.sql("""
                        SELECT %s
                        FROM yumpoo.external_identity identity
                        JOIN yumpoo.identity_user user_record
                          ON user_record.id = identity.user_id
                         AND user_record.company_id = identity.company_id
                        WHERE identity.company_id = :companyId
                          AND identity.provider = 'WECOM'
                          AND identity.external_user_id = :externalUserId
                        FOR UPDATE OF identity, user_record
                        """.formatted(COLUMNS))
                .param("companyId", companyId)
                .param("externalUserId", externalUserId)
                .query(JdbcAuthenticationUserRepository::map)
                .list();
        return single(users);
    }

    @Override
    public Optional<AuthenticationUser> findByUserId(UUID userId) {
        List<AuthenticationUser> users = jdbcClient.sql("""
                        SELECT %s
                        FROM yumpoo.identity_user user_record
                        JOIN yumpoo.external_identity identity
                          ON identity.user_id = user_record.id
                         AND identity.company_id = user_record.company_id
                         AND identity.provider = 'WECOM'
                        WHERE user_record.id = :userId
                        """.formatted(COLUMNS))
                .param("userId", userId)
                .query(JdbcAuthenticationUserRepository::map)
                .list();
        return single(users);
    }

    private static Optional<AuthenticationUser> single(List<AuthenticationUser> users) {
        if (users.size() > 1) {
            throw new IllegalStateException("Authentication identity uniqueness was violated");
        }
        return users.stream().findFirst();
    }

    private static AuthenticationUser map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AuthenticationUser(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                resultSet.getString("display_name"),
                EmploymentStatus.valueOf(resultSet.getString("employment_status")),
                AccountStatus.valueOf(resultSet.getString("account_status")),
                EmploymentStatus.valueOf(resultSet.getString("provider_employment_status")),
                resultSet.getLong("authorization_version"),
                resultSet.getLong("row_version")
        );
    }
}
