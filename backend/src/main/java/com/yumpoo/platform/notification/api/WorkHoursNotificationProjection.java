package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.identityaccess.api.ActiveCompanyAdminQuery;
import com.yumpoo.platform.notification.application.NotificationModels.Reason;
import com.yumpoo.platform.notification.application.NotificationModels.TargetKind;
import com.yumpoo.platform.notification.application.NotificationRepository;
import com.yumpoo.platform.notification.application.NotificationRepository.WorkHours;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

@Component
public class WorkHoursNotificationProjection implements OutboxEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(WorkHoursNotificationProjection.class);
    private final NotificationRepository repository;
    private final NotificationContextPort context;
    private final ActiveCompanyAdminQuery admins;
    public WorkHoursNotificationProjection(NotificationRepository repository, NotificationContextPort context, ActiveCompanyAdminQuery admins) {
        this.repository = repository; this.context = context; this.admins = admins;
    }

    public String consumerName() { return "notification-work-hours-v1"; }
    public Set<EventSubscription> subscriptions() { return Set.of(new EventSubscription("reporting.work_hours_anomaly_detected", 1)); }

    public void consume(DomainEventEnvelope event) {
        try {
            var payload = event.payload();
            UUID user = UUID.fromString(payload.path("userId").asText());
            LocalDate day = LocalDate.parse(payload.path("workDate").asText());
            long duration = payload.path("totalDurationMs").asLong();
            int threshold = payload.path("thresholdMinutes").asInt();
            var recipients = context.activeAccounts(event.companyId(), admins.findUserIds(event.companyId()));
            if (recipients.isEmpty()) return;
            var reasons = new LinkedHashMap<UUID, Reason>();
            recipients.forEach(recipient -> reasons.put(recipient, Reason.WORK_HOURS_ANOMALY));
            repository.append(new NotificationRepository.Event(UUID.randomUUID(), event.companyId(), event.eventId(),
                    event.eventType(), event.eventVersion(), TargetKind.MEMBER_DAY, null, null, null, user, null,
                    event.occurredAt(), null, new WorkHours(day, duration, threshold)), reasons);
        } catch (DataAccessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            LOG.atWarn().setMessage("work hours notification source ignored")
                    .addKeyValue("eventId", event.eventId()).addKeyValue("exceptionType", failure.getClass().getSimpleName()).log();
        }
    }
}
