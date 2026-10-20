package dev.soynx.heim.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.error.BadRequestException;

/**
 * Config revision as HTTP entity tag. Mutating endpoints accept {@code If-Match}; an absent header
 * (or {@code *}) skips the check.
 */
public final class ETags {

    public static final String IF_MATCH = "If-Match";

    private ETags() {
    }

    public static String of(long revision) {
        return "\"" + revision + "\"";
    }

    /** Expected revision from an {@code If-Match} header, or null when no check is requested. */
    public static Long expectedRevision(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        String tag = ifMatch.trim();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        tag = tag.replace("\"", "");
        try {
            return Long.parseLong(tag);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Malformed If-Match header: " + ifMatch);
        }
    }

    public static <T> ResponseEntity<T> ok(Versioned<T> versioned) {
        return ResponseEntity.ok().eTag(of(versioned.revision())).body(versioned.value());
    }

    public static <T> ResponseEntity<T> created(URI location, Versioned<T> versioned) {
        return ResponseEntity.created(location).eTag(of(versioned.revision())).body(versioned.value());
    }

    public static ResponseEntity<Void> noContent(Versioned<?> versioned) {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).eTag(of(versioned.revision())).build();
    }
}
