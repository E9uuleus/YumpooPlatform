package com.yumpoo.platform.identityaccess.application.onboarding;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class OnboardingService {
    private final OnboardingRepository repository;

    public OnboardingService(OnboardingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OnboardingState state(UUID companyId, UUID userId) {
        return new OnboardingState(repository.tours(companyId, userId));
    }

    @Transactional
    public OnboardingState complete(UUID companyId, UUID userId, String tourId, int version) {
        repository.complete(companyId, userId, tourId, version);
        return new OnboardingState(repository.tours(companyId, userId));
    }
}
