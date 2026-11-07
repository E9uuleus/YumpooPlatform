package com.yumpoo.platform.workitem.application.time;

import static com.yumpoo.platform.workitem.application.time.TimeShareCalculator.Share;
import static com.yumpoo.platform.workitem.application.time.TimeTrackingModels.Session;

import java.time.Instant;
import java.util.*;

public interface TimeShareRepository {
    record Window(Instant from, Instant to) {}

    Window snap(UUID companyId, UUID userId, Instant from, Instant to);

    Set<UUID> projects(UUID companyId, UUID userId, Instant from, Instant to);

    void delete(UUID companyId, UUID userId, Instant from, Instant to);

    List<Session> sessions(UUID companyId, UUID userId, Instant from, Instant to);

    void insert(List<Share> shares);
}
