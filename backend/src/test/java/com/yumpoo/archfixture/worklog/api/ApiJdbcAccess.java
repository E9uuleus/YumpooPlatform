package com.yumpoo.archfixture.worklog.api;

import java.sql.Connection;

public final class ApiJdbcAccess {

    private final Connection connection;

    public ApiJdbcAccess(Connection connection) {
        this.connection = connection;
    }

    public Connection connection() {
        return connection;
    }
}
