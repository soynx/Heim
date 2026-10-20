package dev.soynx.heim.config.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Global probe timing, used by every link that does not override it. */
public class ProbeDefaults extends Extensible {

    public static final int DEFAULT_INTERVAL_SECONDS = 60;
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;

    @Min(5)
    @Max(86_400)
    private int intervalSeconds = DEFAULT_INTERVAL_SECONDS;

    @Min(1)
    @Max(300)
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
