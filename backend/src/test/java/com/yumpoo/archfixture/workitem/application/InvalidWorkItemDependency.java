package com.yumpoo.archfixture.workitem.application;

import com.yumpoo.archfixture.notification.infrastructure.NotificationStore;

public final class InvalidWorkItemDependency {

    private final NotificationStore notificationStore;

    public InvalidWorkItemDependency(NotificationStore notificationStore) {
        this.notificationStore = notificationStore;
    }

    public NotificationStore notificationStore() {
        return notificationStore;
    }
}
