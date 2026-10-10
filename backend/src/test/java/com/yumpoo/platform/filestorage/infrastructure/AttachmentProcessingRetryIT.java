package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(properties="yumpoo.outbox.enabled=false")
@Transactional
class AttachmentProcessingRetryIT {
    @Autowired JdbcClient jdbc;
    @Autowired JdbcAttachmentRepository repository;
    @Test void persistentPublishContentionCanBeClaimedAfterTheLegacyThirdAttempt() {
        UUID company=UUID.randomUUID(),project=UUID.randomUUID(),attachment=UUID.randomUUID();
        Instant start=Instant.now();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment(id,company_id,quota_project_id,owner_type,owner_id,original_file_name,
                    file_extension,declared_mime,status,processing_stage,reserved_bytes,uploaded_by_user_id,
                    intent_expires_at,size_bytes,sha256,scan_generation,created_at,updated_at)
                VALUES (:id,:company,:project,'WORK_ITEM',:owner,'retry.txt','txt','text/plain','UPLOADING',
                    'QUEUED_SCAN',1,:user,:now,1,:hash,1,:now,:now)
                """).param("id",attachment).param("company",company).param("project",project)
                .param("owner",UUID.randomUUID()).param("user",UUID.randomUUID()).param("hash","a".repeat(64))
                .param("now",OffsetDateTime.ofInstant(start,ZoneOffset.UTC)).update();
        jdbc.sql("""
                INSERT INTO yumpoo.attachment_scan_task(id,attachment_id,company_id,generation,status,attempt_count,
                    next_attempt_at,created_at,updated_at) VALUES (:task,:id,:company,1,'READY',0,:now,:now,:now)
                """).param("task",UUID.randomUUID()).param("id",attachment).param("company",company)
                .param("now",OffsetDateTime.ofInstant(start,ZoneOffset.UTC)).update();
        for(int attempt=1;attempt<=8;attempt++) {
            Instant now=start.plusSeconds(attempt*2L);
            var claim=repository.claimDue("retry-it",UUID.randomUUID(),now,now.plusSeconds(60)).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(Math.min(attempt,3));
            repository.retry(claim,now.plusSeconds(1),now);
        }
        assertThat(jdbc.sql("SELECT status FROM yumpoo.attachment WHERE id=:id").param("id",attachment).query(String.class).single())
                .isEqualTo("UPLOADING");
        assertThat(jdbc.sql("SELECT status FROM yumpoo.attachment_scan_task WHERE attachment_id=:id")
                .param("id",attachment).query(String.class).single()).isEqualTo("READY");
    }
}
