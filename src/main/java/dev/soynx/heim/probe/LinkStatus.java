package dev.soynx.heim.probe;

import java.time.Instant;
import java.util.Objects;

/** Result of the latest probe of a link, as exposed by {@code /api/status}. */
public record LinkStatus(
        LinkState state,
        Integer statusCode,
        Long latencyMs,
        Instant checkedAt,
        String error) {

    public static final LinkStatus UNKNOWN = new LinkStatus(LinkState.UNKNOWN, null, null, null, null);

    public static LinkStatus up(int statusCode, long latencyMs, Instant checkedAt) {
        return new LinkStatus(LinkState.UP, statusCode, latencyMs, checkedAt, null);
    }

    public static LinkStatus down(Integer statusCode, Long latencyMs, Instant checkedAt, String error) {
        return new LinkStatus(LinkState.DOWN, statusCode, latencyMs, checkedAt, error);
    }

    /** Whether going from {@code previous} to this result is worth pushing to clients. */
    boolean differsFrom(LinkStatus previous) {
        return previous == null || previous.state != state || !Objects.equals(previous.statusCode, statusCode);
    }
}
