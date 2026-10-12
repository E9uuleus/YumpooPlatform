package com.yumpoo.platform.identityaccess.application.onboarding;

import java.util.List;
import java.util.UUID;

public interface OnboardingRepository {
    List<OnboardingTourRecord> tours(UUID companyId, UUID userId);

    /** 版本只升不降；同版本或更低版本的重复写入不改变记录。 */
    void complete(UUID companyId, UUID userId, String tourId, int version);
}
