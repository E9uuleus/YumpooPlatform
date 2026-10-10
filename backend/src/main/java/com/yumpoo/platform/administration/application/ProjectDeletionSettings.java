package com.yumpoo.platform.administration.application;

import java.time.Duration;

public record ProjectDeletionSettings(Duration gracePeriod, Duration reminderLead, int batchSize) {}
