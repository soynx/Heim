package dev.soynx.heim.probe;

import java.util.Map;

/** Body of {@code GET /api/status} and of the SSE {@code snapshot} event. */
public record StatusSnapshot(Map<String, LinkStatus> links, Map<String, ServiceState> services) {
}
