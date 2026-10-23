package com.yumpoo.platform.workitem.application;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class TimeShareMaintainer {
    private final TimeShareRepository shares;
    public TimeShareMaintainer(TimeShareRepository shares) { this.shares=shares; }

    /** Caller holds the user's timer_state lock. Returns projects present before or after rebuilding the window; category shares have none. */
    public Set<UUID> rebuild(UUID companyId, UUID userId, Instant from, Instant to) {
        var window=shares.snap(companyId,userId,from,to);
        var projects=new HashSet<>(shares.projects(companyId,userId,window.from(),window.to()));
        shares.delete(companyId,userId,window.from(),window.to());
        var rebuilt=TimeShareCalculator.segments(shares.sessions(companyId,userId,window.from(),window.to()),window.from(),window.to());
        shares.insert(rebuilt);
        rebuilt.stream().map(TimeShareCalculator.Share::projectId).filter(Objects::nonNull).forEach(projects::add);
        return projects;
    }
}
