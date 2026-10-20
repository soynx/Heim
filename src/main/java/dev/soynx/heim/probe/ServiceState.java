package dev.soynx.heim.probe;

import java.util.Collection;

/** Aggregated state of a service over its probed links. */
public enum ServiceState {
    UP,
    DEGRADED,
    DOWN,
    UNKNOWN;

    /**
     * UNKNOWN if no link has been checked yet; otherwise, over the checked links, UP if all are up,
     * DOWN if none is up and DEGRADED in between. Links still waiting for their first check are
     * ignored so a freshly added link does not flip the card to DEGRADED.
     */
    public static ServiceState aggregate(Collection<LinkStatus> links) {
        int checked = 0;
        int up = 0;
        for (LinkStatus link : links) {
            if (link.state() == LinkState.UNKNOWN) {
                continue;
            }
            checked++;
            if (link.state() == LinkState.UP) {
                up++;
            }
        }
        if (checked == 0) {
            return UNKNOWN;
        }
        if (up == checked) {
            return UP;
        }
        return up == 0 ? DOWN : DEGRADED;
    }
}
