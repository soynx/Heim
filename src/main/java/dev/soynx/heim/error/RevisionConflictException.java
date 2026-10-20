package dev.soynx.heim.error;

/** The client's {@code If-Match} revision is stale. Mapped to HTTP 409. */
public class RevisionConflictException extends RuntimeException {

    private final long expected;
    private final long actual;

    public RevisionConflictException(long expected, long actual) {
        super("Config was modified concurrently (expected revision " + expected + ", current " + actual + ")");
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
