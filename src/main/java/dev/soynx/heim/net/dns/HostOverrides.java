package dev.soynx.heim.net.dns;

import java.util.Map;
import java.util.Optional;

/**
 * Overrides by normalized host name. A {@code *.example.com} key matches every name below
 * {@code example.com}, but not {@code example.com} itself.
 */
record HostOverrides(Map<String, HostTarget> entries) {

    static final String WILDCARD = "*.";

    HostOverrides {
        entries = Map.copyOf(entries);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The exact entry if there is one, otherwise the most specific wildcard. */
    Optional<HostTarget> find(String host) {
        HostTarget exact = entries.get(host);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (int dot = host.indexOf('.'); dot >= 0; dot = host.indexOf('.', dot + 1)) {
            HostTarget wildcard = entries.get(WILDCARD + host.substring(dot + 1));
            if (wildcard != null) {
                return Optional.of(wildcard);
            }
        }
        return Optional.empty();
    }
}
