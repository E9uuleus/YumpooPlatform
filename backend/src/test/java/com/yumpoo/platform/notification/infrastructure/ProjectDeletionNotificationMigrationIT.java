package com.yumpoo.platform.notification.infrastructure;

import com.yumpoo.platform.notification.application.NotificationModels.*;
import com.yumpoo.platform.notification.application.NotificationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ProjectDeletionNotificationMigrationIT {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17.10-alpine");
    final UUID company=UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test void v67ToV69PreservesExistingNotificationsAndWatermarksWithAnIndependentDeletionCutover() {
        var source=dataSource();
        var jdbc=JdbcClient.create(source);
        migration(source,"67").migrate();
        var repository=new JdbcNotificationRepository(jdbc);
        UUID event=seedEvent(jdbc,UUID.randomUUID());
        UUID recipient=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.user_notification(id,company_id,notification_event_id,recipient_user_id,reason)
                VALUES (:id,:company,:event,:recipient,'ASSIGNED')
                """).param("id",UUID.randomUUID()).param("company",company).param("event",event).param("recipient",recipient).update();
        var notifications=jdbc.sql("SELECT * FROM yumpoo.user_notification").query().listOfRows();
        var inboxCutover=repository.acceptedFrom();
        var connectionCutover=repository.connectionAcceptedFrom();
        var before=repository.serverNow();
        var migration=migration(source,"69");
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(repository.acceptedFrom()).isEqualTo(inboxCutover);
        assertThat(repository.connectionAcceptedFrom()).isEqualTo(connectionCutover);
        var deletionCutover=repository.projectDeletionAcceptedFrom();
        assertThat(deletionCutover).isAfterOrEqualTo(before).isBeforeOrEqualTo(repository.serverNow());
        assertThat(jdbc.sql("SELECT * FROM yumpoo.user_notification").query().listOfRows()).isEqualTo(notifications);
        assertThat(jdbc.sql("SELECT deletion_purge_after IS NULL FROM yumpoo.notification_event WHERE id=:event")
                .param("event",event).query(Boolean.class).single()).isTrue();
        for (var reason:List.of(Reason.PROJECT_DELETION_SCHEDULED,Reason.PROJECT_DELETION_REMINDER,Reason.PROJECT_DELETION_CANCELLED)) {
            jdbc.sql("UPDATE yumpoo.user_notification SET reason=:reason WHERE notification_event_id=:event")
                    .param("reason",reason.name()).param("event",event).update();
            assertThat(repository.counts(company,recipient).project()).isOne();
        }
        assertThatThrownBy(()->jdbc.sql("UPDATE yumpoo.user_notification SET reason='UNKNOWN'").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        Instant deadline=Instant.now().plus(30,ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UUID project=UUID.randomUUID(), sourceId=UUID.randomUUID();
        var row=new NotificationRepository.Event(UUID.randomUUID(),company,sourceId,"catalog.project_deletion_scheduled",1,
                TargetKind.PROJECT,project,null,null,null,UUID.randomUUID(),Instant.now(),deadline);
        repository.append(row,Map.of(recipient,Reason.PROJECT_DELETION_SCHEDULED));
        repository.append(new NotificationRepository.Event(UUID.randomUUID(),company,sourceId,row.eventType(),1,
                TargetKind.PROJECT,project,null,null,null,row.actorUserId(),row.occurredAt(),deadline.plusSeconds(60)),
                Map.of(recipient,Reason.PROJECT_DELETION_SCHEDULED));
        var persisted=repository.find(company,recipient,ListState.ALL,Group.PROJECT,null,50);
        assertThat(persisted).hasSize(2);
        assertThat(persisted.stream().filter(n->n.event().sourceEventId().equals(sourceId)).toList()).singleElement()
                .satisfies(n->assertThat(n.event().deletionPurgeAfter()).isEqualTo(deadline));
        assertThat(migration.migrate().migrationsExecuted).isZero();
        assertThat(repository.projectDeletionAcceptedFrom()).isEqualTo(deletionCutover);
    }

    @Test void notificationPurgeBoundsTheCombinedBatchAndCanResumeWithoutCascadingOrTouchingOtherProjects() {
        var source=dataSource();
        migration(source,"69").migrate();
        var jdbc=JdbcClient.create(source);
        var transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        var purger=new JdbcNotificationProjectDataPurger(jdbc);
        UUID project=UUID.randomUUID(), other=UUID.randomUUID();
        UUID event=seedEvent(jdbc,project), otherEvent=seedEvent(jdbc,other);
        seedRecipients(jdbc,event,1201);
        seedRecipients(jdbc,otherEvent,3);
        jdbc.sql("""
                INSERT INTO yumpoo.project_notification_preference(company_id,project_id,user_id,mode,
                    notify_mention,notify_comment,notify_assigned,notify_connection_created)
                SELECT :company,:project,gen_random_uuid(),'MUTED',false,false,false,false FROM generate_series(1,17)
                """).param("company",company).param("project",project).update();
        jdbc.sql("""
                INSERT INTO yumpoo.project_notification_preference(company_id,project_id,user_id,mode,
                    notify_mention,notify_comment,notify_assigned,notify_connection_created)
                VALUES (:company,:project,:user,'MUTED',false,false,false,false)
                """).param("company",company).param("project",other).param("user",UUID.randomUUID()).update();
        long previous=ownedRows(jdbc,project);
        int batches=0;
        boolean remaining;
        do {
            remaining=Boolean.TRUE.equals(transactions.execute(status->purger.purgeBatch(company,project,500)));
            long current=ownedRows(jdbc,project);
            assertThat(previous-current).isBetween(1L,500L);
            assertThat(remaining).isEqualTo(current>0);
            previous=current;
            assertThat(++batches).isLessThan(10);
        } while (remaining);
        assertThat(batches).isEqualTo(3);
        Boolean repeated=transactions.execute(status->purger.purgeBatch(company,project,500));
        assertThat(repeated).isFalse();
        assertThat(ownedRows(jdbc,other)).isEqualTo(5);
        assertThat(purger.stage()).isEqualTo("NOTIFICATION");
        assertThat(purger.order()).isEqualTo(10);
        assertThatThrownBy(()->purger.purgeBatch(company,project,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->purger.purgeBatch(company,project,501)).isInstanceOf(IllegalArgumentException.class);
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
    }
    private Flyway migration(DriverManagerDataSource source,String version) {
        return Flyway.configure().dataSource(source).schemas("yumpoo").defaultSchema("yumpoo")
                .locations("classpath:db/migration").target(version).load();
    }
    private UUID seedEvent(JdbcClient jdbc,UUID project) {
        UUID event=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO yumpoo.notification_event(id,company_id,source_event_id,event_type,payload_schema_version,
                    target_kind,project_id,occurred_at)
                VALUES (:event,:company,:source,'catalog.project_member_added',1,'PROJECT',:project,clock_timestamp())
                """).param("event",event).param("company",company).param("source",UUID.randomUUID()).param("project",project).update();
        return event;
    }
    private void seedRecipients(JdbcClient jdbc,UUID event,int count) {
        jdbc.sql("""
                INSERT INTO yumpoo.user_notification(id,company_id,notification_event_id,recipient_user_id,reason)
                SELECT gen_random_uuid(),:company,:event,gen_random_uuid(),'PROJECT_MEMBER_ADDED' FROM generate_series(1,:count)
                """).param("company",company).param("event",event).param("count",count).update();
    }
    private long ownedRows(JdbcClient jdbc,UUID project) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM yumpoo.notification_event WHERE company_id=:company AND project_id=:project)
                    +(SELECT count(*) FROM yumpoo.user_notification n JOIN yumpoo.notification_event e
                        ON e.company_id=n.company_id AND e.id=n.notification_event_id WHERE e.company_id=:company AND e.project_id=:project)
                    +(SELECT count(*) FROM yumpoo.project_notification_preference WHERE company_id=:company AND project_id=:project)
                """).param("company",company).param("project",project).query(Long.class).single();
    }
}
