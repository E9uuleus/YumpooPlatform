package com.yumpoo.platform.identityaccess.infrastructure.onboarding;

import com.yumpoo.platform.identityaccess.application.onboarding.OnboardingRepository;
import com.yumpoo.platform.identityaccess.application.onboarding.OnboardingTourRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOnboardingRepository implements OnboardingRepository {
    private final JdbcClient jdbc;

    public JdbcOnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OnboardingTourRecord> tours(UUID companyId, UUID userId) {
        return jdbc.sql("""
                SELECT tour_id, version, completed_at FROM yumpoo.user_onboarding_tour
                WHERE company_id = :company AND user_id = :user
                ORDER BY tour_id
                """).param("company", companyId).param("user", userId)
                .query((rs, n) -> new OnboardingTourRecord(rs.getString("tour_id"), rs.getInt("version"),
                        rs.getObject("completed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public void complete(UUID companyId, UUID userId, String tourId, int version) {
        jdbc.sql("""
                INSERT INTO yumpoo.user_onboarding_tour (company_id, user_id, tour_id, version)
                VALUES (:company, :user, :tour, :version)
                ON CONFLICT (company_id, user_id, tour_id) DO UPDATE
                SET version = EXCLUDED.version, completed_at = clock_timestamp()
                WHERE yumpoo.user_onboarding_tour.version < EXCLUDED.version
                """).param("company", companyId).param("user", userId).param("tour", tourId)
                .param("version", version).update();
    }
}
