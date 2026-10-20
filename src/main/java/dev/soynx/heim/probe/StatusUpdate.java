package dev.soynx.heim.probe;

/** Data of the SSE {@code update} event, sent when a link's state or status code changes. */
public record StatusUpdate(String serviceId, String linkId, LinkStatus link, ServiceState service) {
}
