package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class NotificationCursorCodec {
    public String encode(String fingerprint,NotificationRepository.Anchor anchor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1\n"+fingerprint+"\n"+anchor.createdAt()+"\n"+anchor.id()).getBytes(StandardCharsets.UTF_8));
    }
    public NotificationRepository.Anchor decode(String cursor,String fingerprint) {
        if(cursor==null || cursor.isBlank()) return null;
        try {
            if(cursor.length()>2048) throw new IllegalArgumentException();
            String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);
            if(parts.length!=4 || !parts[0].equals("v1") || !parts[1].equals(fingerprint)) throw new IllegalArgumentException();
            return new NotificationRepository.Anchor(Instant.parse(parts[2]),UUID.fromString(parts[3]));
        } catch(RuntimeException failure) { throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED,"通知游标无效或与筛选条件不匹配"); }
    }
}
