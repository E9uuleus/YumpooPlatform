package com.yumpoo.platform.foundation.application.collaboration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared rich-text boundary for collaboration facts such as WorkItemUpdate and FeedbackUpdate. */
public interface CollaborationHtmlSanitizer {

    ParsedHtml parse(String untrustedHtml);

    SanitizedHtml canonicalize(ParsedHtml parsed, Map<UUID, String> authoritativeDisplayNames);

    record ParsedHtml(String safeHtml, List<UUID> mentionedUserIds) {
        public ParsedHtml {
            mentionedUserIds = List.copyOf(mentionedUserIds);
        }
    }

    record SanitizedHtml(String bodyHtml, String bodyText, List<UUID> mentionedUserIds) {
        public SanitizedHtml {
            mentionedUserIds = List.copyOf(mentionedUserIds);
        }
    }
}
