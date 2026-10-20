package dev.soynx.heim.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * In-memory latest probe result per link. Only links registered via {@link #retain} are tracked;
 * results for anything else are dropped, so a late probe of a removed link cannot resurrect it.
 */
@Component
public class StatusRegistry {

    /** serviceId -> probed link ids, in config order. */
    private Map<String, List<String>> layout = Map.of();
    private final Map<String, LinkStatus> links = new LinkedHashMap<>();

    /**
     * Replaces the set of probed links. Results of kept links survive, new links start as UNKNOWN.
     *
     * @return whether the layout differs from the previous one
     */
    public synchronized boolean retain(Map<String, List<String>> probedLinks) {
        Map<String, List<String>> next = new LinkedHashMap<>();
        probedLinks.forEach((serviceId, linkIds) -> next.put(serviceId, List.copyOf(linkIds)));
        boolean changed = !next.equals(layout);
        Map<String, LinkStatus> kept = new LinkedHashMap<>();
        next.values().forEach(ids -> ids.forEach(id -> kept.put(id, links.getOrDefault(id, LinkStatus.UNKNOWN))));
        links.clear();
        links.putAll(kept);
        layout = next;
        return changed;
    }

    /** Stores a result; returns the update to broadcast if state or status code changed. */
    public synchronized Optional<StatusUpdate> record(String serviceId, String linkId, LinkStatus status) {
        List<String> serviceLinks = layout.get(serviceId);
        if (serviceLinks == null || !serviceLinks.contains(linkId)) {
            return Optional.empty();
        }
        LinkStatus previous = links.put(linkId, status);
        if (!status.differsFrom(previous)) {
            return Optional.empty();
        }
        return Optional.of(new StatusUpdate(serviceId, linkId, status, serviceState(serviceLinks)));
    }

    public synchronized StatusSnapshot snapshot() {
        Map<String, ServiceState> services = new LinkedHashMap<>();
        layout.forEach((serviceId, linkIds) -> services.put(serviceId, serviceState(linkIds)));
        return new StatusSnapshot(new LinkedHashMap<>(links), services);
    }

    private ServiceState serviceState(List<String> linkIds) {
        List<LinkStatus> statuses = new ArrayList<>(linkIds.size());
        linkIds.stream().map(links::get).filter(Objects::nonNull).forEach(statuses::add);
        return ServiceState.aggregate(statuses);
    }
}
