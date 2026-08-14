package com.yumpoo.platform.administration.infrastructure.governance;

import com.yumpoo.platform.administration.application.governance.GovernanceIssuePage;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueQuery;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueQueryUseCase;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueStatus;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueType;
import com.yumpoo.platform.administration.application.governance.GovernanceIssueView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcGovernanceIssueQuery implements GovernanceIssueQueryUseCase {

    private final JdbcClient jdbcClient;

    public JdbcGovernanceIssueQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public GovernanceIssuePage find(GovernanceIssueQuery query) {
        List<Object> parameters = new ArrayList<>();
        String predicates = predicates(query, parameters);
        JdbcClient.StatementSpec countStatement = jdbcClient.sql(
                "SELECT count(*) FROM yumpoo.governance_issue WHERE company_id = ?" + predicates)
                .param(query.companyId());
        for (Object parameter : parameters) {
            countStatement = countStatement.param(parameter);
        }
        long total = countStatement.query(Long.class).single();

        JdbcClient.StatementSpec pageStatement = jdbcClient.sql("""
                        SELECT id, company_id, issue_type, status, safe_summary_code,
                               detected_event_id, detected_at, resolved_event_id, resolved_at,
                               row_version
                        FROM yumpoo.governance_issue
                        WHERE company_id = ?
                        """ + predicates + " ORDER BY detected_at DESC, id ASC LIMIT ? OFFSET ?")
                .param(query.companyId());
        for (Object parameter : parameters) {
            pageStatement = pageStatement.param(parameter);
        }
        List<GovernanceIssueView> items = pageStatement
                .param(query.pageSize())
                .param((long) query.page() * query.pageSize())
                .query(JdbcGovernanceIssueQuery::map)
                .list();
        return new GovernanceIssuePage(items, query.page(), query.pageSize(), total);
    }

    private String predicates(GovernanceIssueQuery query, List<Object> parameters) {
        StringBuilder sql = new StringBuilder();
        if (query.issueType() != null) {
            sql.append(" AND issue_type = ?");
            parameters.add(query.issueType().name());
        }
        if (query.status() != null) {
            sql.append(" AND status = ?");
            parameters.add(query.status().name());
        }
        return sql.toString();
    }

    private static GovernanceIssueView map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new GovernanceIssueView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                GovernanceIssueType.valueOf(resultSet.getString("issue_type")),
                GovernanceIssueStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("safe_summary_code"),
                resultSet.getObject("detected_event_id", UUID.class),
                resultSet.getObject("detected_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("resolved_event_id", UUID.class),
                resultSet.getObject("resolved_at", OffsetDateTime.class) == null
                        ? null : resultSet.getObject("resolved_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("row_version")
        );
    }
}
