package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.foundation.application.concurrency.ConditionalUpdateFailure;
import com.yumpoo.platform.foundation.application.concurrency.ConditionalUpdateGuard;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusChangeCommand;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusRepository;
import com.yumpoo.platform.identityaccess.application.account.AccountStatusSnapshot;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcAccountStatusRepository implements AccountStatusRepository {

    private static final String RESULT_COLUMNS = """
            id, company_id, employment_status, account_status,
            authorization_version, row_version
            """;

    private final JdbcClient jdbcClient;

    public JdbcAccountStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public AccountStatusSnapshot change(AccountStatusChangeCommand command) {
        List<AccountStatusSnapshot> changed = command.desiredStatus() == AccountStatus.DISABLED
                ? disable(command)
                : enable(command);
        ConditionalUpdateGuard.requireSingleRowUpdated(
                changed.size(),
                () -> classifyFailure(command)
        );
        return changed.getFirst();
    }

    private List<AccountStatusSnapshot> disable(AccountStatusChangeCommand command) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.identity_user target
                        SET account_status = 'DISABLED',
                            account_disabled_at = transaction_timestamp(),
                            account_disabled_by_user_id = :actorUserId,
                            account_disabled_reason = :reason,
                            authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE target.id = :targetUserId
                          AND target.company_id = :companyId
                          AND target.account_status = 'ENABLED'
                          AND target.row_version = :expectedRowVersion
                          AND EXISTS (
                              SELECT 1
                              FROM yumpoo.identity_user actor
                              WHERE actor.id = :actorUserId
                                AND actor.company_id = :companyId
                          )
                        RETURNING %s
                        """.formatted(RESULT_COLUMNS))
                .param("actorUserId", command.actorUserId())
                .param("reason", command.reason())
                .param("targetUserId", command.targetUserId())
                .param("companyId", command.companyId())
                .param("expectedRowVersion", command.expectedRowVersion())
                .query(JdbcAccountStatusRepository::mapSnapshot)
                .list();
    }

    private List<AccountStatusSnapshot> enable(AccountStatusChangeCommand command) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.identity_user target
                        SET account_status = 'ENABLED',
                            authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE target.id = :targetUserId
                          AND target.company_id = :companyId
                          AND target.account_status = 'DISABLED'
                          AND target.row_version = :expectedRowVersion
                          AND EXISTS (
                              SELECT 1
                              FROM yumpoo.identity_user actor
                              WHERE actor.id = :actorUserId
                                AND actor.company_id = :companyId
                          )
                        RETURNING %s
                        """.formatted(RESULT_COLUMNS))
                .param("actorUserId", command.actorUserId())
                .param("targetUserId", command.targetUserId())
                .param("companyId", command.companyId())
                .param("expectedRowVersion", command.expectedRowVersion())
                .query(JdbcAccountStatusRepository::mapSnapshot)
                .list();
    }

    private ConditionalUpdateFailure classifyFailure(AccountStatusChangeCommand command) {
        Optional<AccountState> visible = jdbcClient.sql("""
                        SELECT target.row_version, target.account_status
                        FROM yumpoo.identity_user target
                        WHERE target.id = :targetUserId
                          AND target.company_id = :companyId
                          AND EXISTS (
                              SELECT 1
                              FROM yumpoo.identity_user actor
                              WHERE actor.id = :actorUserId
                                AND actor.company_id = :companyId
                          )
                        """)
                .param("targetUserId", command.targetUserId())
                .param("companyId", command.companyId())
                .param("actorUserId", command.actorUserId())
                .query((resultSet, rowNumber) -> new AccountState(
                        resultSet.getLong("row_version"),
                        AccountStatus.valueOf(resultSet.getString("account_status"))
                ))
                .optional();
        if (visible.isEmpty()) {
            return ConditionalUpdateFailure.RESOURCE_NOT_VISIBLE;
        }
        if (visible.orElseThrow().rowVersion() != command.expectedRowVersion()) {
            return ConditionalUpdateFailure.VERSION_CONFLICT;
        }
        return ConditionalUpdateFailure.INVALID_STATE;
    }

    private static AccountStatusSnapshot mapSnapshot(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AccountStatusSnapshot(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getObject("company_id", java.util.UUID.class),
                EmploymentStatus.valueOf(resultSet.getString("employment_status")),
                AccountStatus.valueOf(resultSet.getString("account_status")),
                resultSet.getLong("authorization_version"),
                resultSet.getLong("row_version")
        );
    }

    private record AccountState(long rowVersion, AccountStatus status) {
    }
}
