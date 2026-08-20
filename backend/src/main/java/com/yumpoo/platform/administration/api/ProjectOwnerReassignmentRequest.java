package com.yumpoo.platform.administration.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ProjectOwnerReassignmentRequest(@NotNull UUID newOwnerUserId, String reason) {}
