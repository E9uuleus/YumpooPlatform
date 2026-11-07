package com.yumpoo.platform.identityaccess.application.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserAuthorizationQueryService {

    private final SessionRepository repository;

    public UserAuthorizationQueryService(SessionRepository repository) {
        this.repository = repository;
    }

    public Optional<UserAuthorizationRecord> findByUserId(UUID userId) {
        return repository.findUser(userId);
    }
}
