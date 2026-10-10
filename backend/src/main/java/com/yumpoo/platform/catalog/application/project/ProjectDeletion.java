package com.yumpoo.platform.catalog.application.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectDeletion(Instant requestedAt,UUID requestedBy,Instant purgeAfter) {}
