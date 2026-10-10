package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.foundation.application.purge.ProjectDataPurger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
public class JdbcCatalogProjectDataPurger implements ProjectDataPurger {
    private final JdbcClient jdbc;
    public JdbcCatalogProjectDataPurger(JdbcClient jdbc) { this.jdbc=jdbc; }
    public String stage() { return "CATALOG"; }
    public int order() { return 70; }
    @Transactional
    public boolean purgeBatch(UUID companyId,UUID projectId,int limit) {
        if(limit<1 || limit>500) throw new IllegalArgumentException("purge limit must be between 1 and 500");
        // The marked project is finalized in a separate atomic transaction with its completion event and security audit.
        return jdbc.sql("""
                DELETE FROM yumpoo.project_membership WHERE ctid IN (SELECT ctid FROM yumpoo.project_membership
                    WHERE company_id=:company AND project_id=:project LIMIT :limit)
                """).param("company",companyId).param("project",projectId).param("limit",limit).update()>0;
    }
}
