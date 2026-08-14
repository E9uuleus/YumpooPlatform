package com.yumpoo.platform.foundation.api.http;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只接受双引号包围的单个十进制强 ETag。
 */
@Component
public final class IfMatchParser {

    public static final String HEADER_NAME = "If-Match";

    private static final Pattern STRONG_ETAG = Pattern.compile("^\"([0-9]+)\"$");

    public long parseForVisibleResource(boolean resourceVisible, String headerValue) {
        if (!resourceVisible) {
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
        if (headerValue == null) {
            throw new ApplicationException(StandardErrorCode.PRECONDITION_REQUIRED);
        }
        if (headerValue.isBlank()) {
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        }

        Matcher matcher = STRONG_ETAG.matcher(headerValue);
        if (!matcher.matches()) {
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            throw new ApplicationException(StandardErrorCode.MALFORMED_REQUEST);
        }
    }

    public static String format(long rowVersion) {
        return StrongEtag.format(rowVersion);
    }
}
