package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.session.ActiveSessionQueryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ActiveSessionQueryAdapter implements ActiveSessionQuery {

    private final ActiveSessionQueryRepository repository;

    public ActiveSessionQueryAdapter(ActiveSessionQueryRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Session> findActive(UUID companyId, Instant now) {
        return repository
            .find(companyId, now)
            .stream()
            .map(s ->
                new Session(
                    s.id(),
                    s.companyId(),
                    s.userId(),
                    s.displayName(),
                    s.clientType(),
                    s.clientVersion(),
                    s.issuedAt(),
                    s.lastSeenAt(),
                    s.expiresAt()
                )
            )
            .toList();
    }

    @Override
    public Counts countActive(Instant now) {
        var result = repository.count(now);
        return new Counts(result.online(), result.idle());
    }
}
