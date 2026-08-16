package com.yumpoo.platform.identityaccess.infrastructure.verification;

import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureState;
import com.yumpoo.platform.identityaccess.application.verification.IdentityAcceptanceFixtureStateQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class JdbcIdentityAcceptanceFixtureStateQuery
        implements IdentityAcceptanceFixtureStateQuery {

    private final JdbcClient jdbcClient;

    public JdbcIdentityAcceptanceFixtureStateQuery(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public IdentityAcceptanceFixtureState current() {
        return new IdentityAcceptanceFixtureState(
                count("identity_user"),
                count("external_identity"),
                count("platform_role_assignment")
        );
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT count(*) FROM yumpoo." + table)
                .query(Long.class)
                .single();
    }
}
