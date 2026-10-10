package com.yumpoo.platform.notification.infrastructure;

import com.yumpoo.platform.notification.application.NotificationModels.*;
import com.yumpoo.platform.notification.application.NotificationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ConnectionNotificationMigrationIT {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres:17.10-alpine"));

    @Test void v63PreservesInboxRowsAndOldWatermarkWhileEnablingOnlyTheNewReason() {
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=JdbcClient.create(dataSource);
        var old=Flyway.configure().dataSource(dataSource).schemas("yumpoo").defaultSchema("yumpoo")
                .locations("classpath:db/migration").target("62").load();
        old.migrate();
        var repository=new JdbcNotificationRepository(jdbc);
        UUID company=UUID.fromString("00000000-0000-4000-8000-000000000001"), recipient=UUID.randomUUID();
        var event=new NotificationRepository.Event(UUID.randomUUID(),company,UUID.randomUUID(),"workitem.work_item_assigned",1,
                TargetKind.WORK_ITEM,UUID.randomUUID(),UUID.randomUUID(),null,null,UUID.randomUUID(),Instant.now());
        jdbc.sql("""
                INSERT INTO yumpoo.notification_event
                (id,company_id,source_event_id,event_type,payload_schema_version,target_kind,project_id,work_item_id,actor_user_id,occurred_at)
                VALUES (:id,:company,:source,'workitem.work_item_assigned',1,'WORK_ITEM',:project,:item,:actor,clock_timestamp())
                """).param("id",event.id()).param("company",company).param("source",event.sourceEventId())
                .param("project",event.projectId()).param("item",event.workItemId()).param("actor",event.actorUserId()).update();
        jdbc.sql("""
                INSERT INTO yumpoo.user_notification (id,company_id,notification_event_id,recipient_user_id,reason)
                VALUES (:id,:company,:event,:recipient,'ASSIGNED')
                """).param("id",UUID.randomUUID()).param("company",company).param("event",event.id()).param("recipient",recipient).update();
        var events=jdbc.sql("SELECT * FROM yumpoo.notification_event").query().listOfRows();
        var notifications=jdbc.sql("SELECT * FROM yumpoo.user_notification").query().listOfRows();
        var oldCutover=repository.acceptedFrom();
        var before=repository.serverNow();
        var migration=Flyway.configure().dataSource(dataSource).schemas("yumpoo").defaultSchema("yumpoo")
                .locations("classpath:db/migration").target("63").load();
        assertThat(migration.migrate().migrationsExecuted).isOne();
        assertThat(repository.acceptedFrom()).isEqualTo(oldCutover);
        var newCutover=repository.connectionAcceptedFrom();
        assertThat(newCutover).isAfterOrEqualTo(before).isBeforeOrEqualTo(repository.serverNow());
        assertThat(jdbc.sql("SELECT * FROM yumpoo.notification_event").query().listOfRows()).isEqualTo(events);
        assertThat(jdbc.sql("SELECT * FROM yumpoo.user_notification").query().listOfRows()).isEqualTo(notifications);
        jdbc.sql("UPDATE yumpoo.user_notification SET reason='CONNECTION_CREATED'").update();
        assertThat(repository.counts(company,recipient).project()).isOne();
        assertThatThrownBy(()->jdbc.sql("UPDATE yumpoo.user_notification SET reason='UNKNOWN'").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(repository.connectionAcceptedFrom()).isEqualTo(newCutover);
    }
}
