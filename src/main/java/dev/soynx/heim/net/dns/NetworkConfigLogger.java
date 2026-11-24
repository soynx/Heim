package dev.soynx.heim.net.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the effective network overrides once, to make deployments easy to check. */
@Component
class NetworkConfigLogger {

    private static final Logger log = LoggerFactory.getLogger(NetworkConfigLogger.class);

    @EventListener(ApplicationReadyEvent.class)
    void logConfig() {
        NetworkConfig config = HeimAddressResolverProvider.activeConfig();
        if (!config.isEmpty()) {
            log.info("Network overrides: DNS servers {}, search domains {}, hosts {}",
                    config.dnsServers(), config.searchDomains(), config.hosts().entries());
        }
    }
}
