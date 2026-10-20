package dev.soynx.heim.config.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Health probe settings of a single link. Null timings fall back to {@link ProbeDefaults}. */
public class ProbeConfig extends Extensible {

    public static final String DEFAULT_EXPECTED_STATUS = "200-399";
    /** Comma-separated status codes or ranges, e.g. {@code "200,204,300-399"}. */
    public static final String EXPECTED_STATUS_PATTERN =
            "^\\s*[1-5]\\d\\d(\\s*-\\s*[1-5]\\d\\d)?(\\s*,\\s*[1-5]\\d\\d(\\s*-\\s*[1-5]\\d\\d)?)*\\s*$";

    private boolean enabled;
    /** Probe target; null means the link URL. */
    private String url;
    private ProbeMethod method = ProbeMethod.GET;
    @Pattern(regexp = EXPECTED_STATUS_PATTERN, message = "must be status codes or ranges like 200,204,300-399")
    private String expectedStatus = DEFAULT_EXPECTED_STATUS;
    private boolean ignoreTls;
    private boolean followRedirects;
    @Min(5)
    @Max(86_400)
    private Integer intervalSeconds;
    @Min(1)
    @Max(300)
    private Integer timeoutSeconds;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public ProbeMethod getMethod() {
        return method;
    }

    public void setMethod(ProbeMethod method) {
        this.method = method;
    }

    public String getExpectedStatus() {
        return expectedStatus;
    }

    public void setExpectedStatus(String expectedStatus) {
        this.expectedStatus = expectedStatus;
    }

    public boolean isIgnoreTls() {
        return ignoreTls;
    }

    public void setIgnoreTls(boolean ignoreTls) {
        this.ignoreTls = ignoreTls;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(Integer intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
