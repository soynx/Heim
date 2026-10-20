package dev.soynx.heim.error;

/** Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException service(String id) {
        return new NotFoundException("Service not found: " + id);
    }

    public static NotFoundException tab(String id) {
        return new NotFoundException("Tab not found: " + id);
    }
}
