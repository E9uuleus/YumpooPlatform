package com.yumpoo.platform.notification.application;

import static org.assertj.core.api.Assertions.*;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationCursorCodecTest {
    @Test
    void cursorBindsUserAndFiltersAndRejectsMalformedInput() {
        var codec = new NotificationCursorCodec();
        var anchor = new NotificationRepository.Anchor(Instant.now(), UUID.randomUUID());
        String cursor = codec.encode("company/user/UNREAD/MENTION", anchor);
        assertThat(codec.decode(cursor, "company/user/UNREAD/MENTION")).isEqualTo(anchor);
        assertThatThrownBy(() -> codec.decode(cursor, "company/other/UNREAD/MENTION"))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> codec.decode("invalid!", "company/user/ALL/null"))
                .isInstanceOf(ApplicationException.class);
    }
}
