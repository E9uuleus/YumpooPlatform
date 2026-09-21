package com.yumpoo.platform.filestorage.infrastructure;

import com.yumpoo.platform.filestorage.application.BlobVerification;
import com.yumpoo.platform.filestorage.application.QuarantineStorage;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(properties = "yumpoo.outbox.enabled=false")
@Transactional
class AttachmentMaintenanceCursorIT {
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRE_INTENTS", "VERIFY_BLOBS", "RECONCILE_QUOTAS", "RECONCILE_SCANS"})
    void nullAndNonNullCursorsExecuteOnPostgres(String phase) {
        var service = service();
        var id = run(phase);
        assertThat(service.resumeOneBatch("cursor-test")).isPresent();
        jdbc.sql("UPDATE yumpoo.attachment_maintenance_run SET phase=:phase,cursor_value='zzzz',lease_until=transaction_timestamp()-interval '1 day' WHERE id=:id")
                .param("phase", phase).param("id", id).update();
        assertThat(service.resumeOneBatch("cursor-test")).isPresent();
        assertThat(jdbc.sql("SELECT status FROM yumpoo.attachment_maintenance_run WHERE id=:id").param("id", id).query(String.class).single()).isEqualTo("RUNNING");
    }

    @Test
    void blobPaginationResumesAfterTheLastProcessedKey() throws Exception {
        var storage = mock(QuarantineStorage.class);
        when(storage.inspect(any())).thenReturn(BlobVerification.VERIFIED);
        var properties = new AttachmentProperties(); properties.setMaintenanceBatchSize(1);
        var service = new JdbcAttachmentMaintenanceService(jdbc, storage, properties, clock, transactions);
        var id = run("VERIFY_BLOBS");
        for (var prefix : new String[]{"a", "b"}) {
            String hash = prefix.repeat(64);
            jdbc.sql("INSERT INTO yumpoo.attachment_blob(storage_key,sha256,size_bytes,created_at,updated_at) VALUES (:key,:hash,1,transaction_timestamp(),transaction_timestamp())")
                    .param("key", "sha256/" + prefix.repeat(2) + "/" + prefix.repeat(2) + "/" + hash).param("hash", hash).update();
        }
        for (int page = 0; page < 3; page++) assertThat(service.resumeOneBatch("cursor-test")).isPresent();
        assertThat(jdbc.sql("SELECT verified_blobs FROM yumpoo.attachment_maintenance_run WHERE id=:id").param("id", id).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT phase FROM yumpoo.attachment_maintenance_run WHERE id=:id").param("id", id).query(String.class).single()).isEqualTo("RECONCILE_QUOTAS");
    }

    private JdbcAttachmentMaintenanceService service() {
        return new JdbcAttachmentMaintenanceService(jdbc, mock(QuarantineStorage.class), new AttachmentProperties(), clock, transactions);
    }
    private UUID run(String phase) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO yumpoo.attachment_maintenance_run(id,status,phase,dry_run,lease_owner,lease_token,lease_until,started_at,updated_at) VALUES (:id,'RUNNING',:phase,true,'test',:token,transaction_timestamp()-interval '1 day',transaction_timestamp(),transaction_timestamp())")
                .param("id", id).param("phase", phase).param("token", UUID.randomUUID()).update();
        return id;
    }
}
