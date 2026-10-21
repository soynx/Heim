package dev.soynx.heim.error;

/**
 * The client's {@code If-Match} tag is stale. Mapped to HTTP 409 with the current tag as
 * {@code ETag}.
 */
public class RevisionConflictException extends RuntimeException {

    private final String currentTag;

    /** Stale global config revision. */
    public RevisionConflictException(long expected, long actual) {
        this("Config was modified concurrently (expected revision " + expected + ", current " + actual + ")",
                String.valueOf(actual));
    }

    /** Stale resource tag; {@code currentTag} is the unquoted tag the resource has now. */
    public RevisionConflictException(String message, String currentTag) {
        super(message);
        this.currentTag = currentTag;
    }

    public String currentTag() {
        return currentTag;
    }
}
