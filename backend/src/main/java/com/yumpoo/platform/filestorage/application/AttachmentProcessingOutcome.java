package com.yumpoo.platform.filestorage.application;

import com.yumpoo.platform.filestorage.domain.AttachmentRejectedCode;

import java.util.Objects;

public sealed interface AttachmentProcessingOutcome {

    record Available(
            PublishedBlob blob,
            DetectedAttachmentContent detectedContent
    ) implements AttachmentProcessingOutcome {
        public Available {
            Objects.requireNonNull(blob, "blob must not be null");
            Objects.requireNonNull(detectedContent, "detectedContent must not be null");
        }
    }

    record Rejected(
            AttachmentRejectedCode rejectedCode,
            boolean quarantinedContentRetained,
            boolean publishedOrphanRetained
    ) implements AttachmentProcessingOutcome {
        public Rejected {
            Objects.requireNonNull(rejectedCode, "rejectedCode must not be null");
        }
    }
}
