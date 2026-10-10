package com.yumpoo.platform.administration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "yumpoo.projects.deletion")
public class ProjectDeletionProperties {
    private Duration gracePeriod = Duration.ofDays(30);
    private Duration reminderLead = Duration.ofDays(1);
    private Duration purgePollDelay = Duration.ofMinutes(1);
    private int purgeBatchSize = 500;
    public Duration getGracePeriod() { return gracePeriod; }
    public void setGracePeriod(Duration value) { gracePeriod = value; }
    public Duration getReminderLead() { return reminderLead; }
    public void setReminderLead(Duration value) { reminderLead = value; }
    public Duration getPurgePollDelay() { return purgePollDelay; }
    public void setPurgePollDelay(Duration value) { purgePollDelay = value; }
    public int getPurgeBatchSize() { return purgeBatchSize; }
    public void setPurgeBatchSize(int value) { purgeBatchSize = value; }
    public void validate() {
        if (gracePeriod == null || gracePeriod.isNegative() || gracePeriod.isZero()
                || reminderLead == null || reminderLead.isNegative() || reminderLead.isZero()
                || reminderLead.compareTo(gracePeriod) >= 0
                || purgePollDelay == null || purgePollDelay.isNegative() || purgePollDelay.isZero()
                || purgeBatchSize < 1 || purgeBatchSize > 500) {
            throw new IllegalStateException("project deletion timing or batch configuration is invalid");
        }
    }
}
