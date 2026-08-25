package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.filestorage.api.AttachmentOwnerType;

import java.util.UUID;

public record AttachmentIntentCommand(AttachmentOwnerType ownerType, UUID ownerId,
        String originalFileName, String declaredMime, Long sizeBytes) {}
