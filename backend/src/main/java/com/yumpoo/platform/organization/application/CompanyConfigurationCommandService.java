package com.yumpoo.platform.organization.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyConfigurationCommandService {
    private final CompanyRepository repository;

    public CompanyConfigurationCommandService(CompanyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean rename(String displayName, long expectedVersion, Instant now) {
        return repository.rename(repository.current().id(), displayName, expectedVersion, now);
    }
}
