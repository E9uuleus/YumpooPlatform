package com.yumpoo.platform.identityaccess.application.onboarding;

import java.time.Instant;

public record OnboardingTourRecord(String tourId, int version, Instant completedAt) {
}
