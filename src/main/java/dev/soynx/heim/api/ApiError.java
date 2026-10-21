package dev.soynx.heim.api;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * JSON body of every error response. {@code error} is a stable machine-readable code derived from the
 * status (e.g. {@code not-found}); {@code message} is for people.
 */
public record ApiError(String error, String message) {

    public static ApiError of(HttpStatusCode status, String message) {
        return new ApiError(code(status), message);
    }

    static String code(HttpStatusCode status) {
        return switch (status.value()) {
            case 413 -> "payload-too-large";
            default -> {
                HttpStatus resolved = HttpStatus.resolve(status.value());
                yield resolved != null
                        ? resolved.getReasonPhrase().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                        : "http-" + status.value();
            }
        };
    }
}
