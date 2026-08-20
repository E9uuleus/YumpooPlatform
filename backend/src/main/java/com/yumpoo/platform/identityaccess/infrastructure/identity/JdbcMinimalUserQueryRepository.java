package com.yumpoo.platform.identityaccess.infrastructure.identity;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.application.session.MinimalUserQueryRepository;
import com.yumpoo.platform.identityaccess.application.session.MinimalUserRecord;
import com.yumpoo.platform.identityaccess.application.session.MinimalUserRecordPage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcMinimalUserQueryRepository implements MinimalUserQueryRepository {
    private final JdbcClient jdbcClient;

    public JdbcMinimalUserQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<MinimalUserRecord> findByUserIds(UUID companyId, Collection<UUID> userIds) {
        return jdbcClient.sql("""
                SELECT id, company_id, display_name, employment_status, account_status
                FROM yumpoo.identity_user
                WHERE company_id = :companyId AND id IN (:userIds)
                """).param("companyId", companyId).param("userIds", userIds)
                .query((rs, row) -> new MinimalUserRecord(
                        rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("display_name"), rs.getString("employment_status"),
                        rs.getString("account_status"))).list();
    }

    @Override
    public MinimalUserRecordPage findActiveEnabledByName(UUID companyId, String name, OffsetPageRequest page) {
        String predicate = """
                company_id = :companyId AND employment_status = 'ACTIVE'
                AND account_status = 'ENABLED'
                AND position(lower(:name) in lower(display_name)) > 0
                """;
        long total = jdbcClient.sql("SELECT count(*) FROM yumpoo.identity_user WHERE " + predicate)
                .param("companyId", companyId).param("name", name).query(Long.class).single();
        List<MinimalUserRecord> items = jdbcClient.sql("""
                SELECT id, company_id, display_name, employment_status, account_status
                FROM yumpoo.identity_user WHERE %s
                ORDER BY lower(display_name), id LIMIT :limit OFFSET :offset
                """.formatted(predicate)).param("companyId", companyId).param("name", name)
                .param("limit", page.size()).param("offset", (long) page.page() * page.size())
                .query((rs, row) -> new MinimalUserRecord(
                        rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("display_name"), rs.getString("employment_status"),
                        rs.getString("account_status"))).list();
        return new MinimalUserRecordPage(items, total);
    }
}
