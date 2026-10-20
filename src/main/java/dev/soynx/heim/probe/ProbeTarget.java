package dev.soynx.heim.probe;

import java.time.Duration;

import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.ProbeConfig;
import dev.soynx.heim.config.model.ProbeDefaults;
import dev.soynx.heim.config.model.ProbeMethod;
import dev.soynx.heim.config.model.Service;

/**
 * Effective, immutable probe settings of one link with defaults applied. Two equal targets need no
 * rescheduling, so this record is what the scheduler compares on every config change.
 */
public record ProbeTarget(
        String serviceId,
        String linkId,
        String url,
        ProbeMethod method,
        String expectedStatus,
        boolean ignoreTls,
        boolean followRedirects,
        Duration interval,
        Duration timeout) {

    static ProbeTarget of(Service service, Link link, ProbeDefaults defaults) {
        ProbeConfig probe = link.getProbe();
        String url = probe.getUrl() != null && !probe.getUrl().isBlank() ? probe.getUrl() : link.getUrl();
        int interval = probe.getIntervalSeconds() != null ? probe.getIntervalSeconds() : defaults.getIntervalSeconds();
        int timeout = probe.getTimeoutSeconds() != null ? probe.getTimeoutSeconds() : defaults.getTimeoutSeconds();
        return new ProbeTarget(service.getId(), link.getId(), url,
                probe.getMethod() != null ? probe.getMethod() : ProbeMethod.GET,
                probe.getExpectedStatus(), probe.isIgnoreTls(), probe.isFollowRedirects(),
                Duration.ofSeconds(interval), Duration.ofSeconds(timeout));
    }
}
