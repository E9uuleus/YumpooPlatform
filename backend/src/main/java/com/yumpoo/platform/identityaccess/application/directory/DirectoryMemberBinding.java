package com.yumpoo.platform.identityaccess.application.directory;

import com.yumpoo.platform.identityaccess.domain.identity.ExternalIdentity;
import com.yumpoo.platform.identityaccess.domain.identity.User;

import java.util.Objects;

public record DirectoryMemberBinding(User user, ExternalIdentity externalIdentity) {

    public DirectoryMemberBinding {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(externalIdentity, "externalIdentity must not be null");
        if (!user.id().equals(externalIdentity.userId())
                || !user.companyId().equals(externalIdentity.companyId())) {
            throw new IllegalArgumentException("directory member binding is inconsistent");
        }
    }

    @Override
    public String toString() {
        return "DirectoryMemberBinding[userId=" + user.id()
                + ", externalIdentityId=" + externalIdentity.id()
                + ", personalData=REDACTED]";
    }
}
