package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.filestorage.api.AttachmentOwnerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AttachmentIntentCreateRequest(
        @NotNull AttachmentOwnerType ownerType,
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 255) String originalFileName,
        @NotBlank @Size(max = 160) String declaredMime,
        Long sizeBytes
) {}
