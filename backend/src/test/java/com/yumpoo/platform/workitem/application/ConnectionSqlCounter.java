package com.yumpoo.platform.workitem.application;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

final class ConnectionSqlCounter {
    private static final ThreadLocal<AtomicInteger> COUNTER = new ThreadLocal<>();
    private ConnectionSqlCounter() {}

    static int count(Runnable query) {
        var count = new AtomicInteger();
        COUNTER.set(count);
        try { query.run(); return count.get(); }
        finally { COUNTER.remove(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        static BeanPostProcessor connectionSqlCounter() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("dataSource") || !(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException { return counted(super.getConnection()); }
                        @Override public Connection getConnection(String user, String password) throws SQLException {
                            return counted(super.getConnection(user, password));
                        }
                    };
                }
            };
        }
    }

    private static Connection counted(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && COUNTER.get() != null) COUNTER.get().incrementAndGet();
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }
}
