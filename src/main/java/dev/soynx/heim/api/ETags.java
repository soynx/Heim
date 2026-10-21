package dev.soynx.heim.api;

import java.net.URI;

import org.springframework.http.ResponseEntity;

import dev.soynx.heim.config.Tagged;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.error.BadRequestException;

/**
 * HTTP entity tags. Single resources (a service, a tab, the settings) carry a per-resource tag
 * ({@link dev.soynx.heim.config.ResourceTags}); collections carry a tag of their id order; the
 * dashboard carries the global config revision. Mutating endpoints accept {@code If-Match}; an
 * absent header (or {@code *}) skips the check.
 */
public final class ETags {

    public static final String IF_MATCH = "If-Match";

    private ETags() {
    }

    public static String of(long revision) {
        return of(String.valueOf(revision));
    }

    public static String of(String tag) {
        return "\"" + tag + "\"";
    }

    /** Unquoted tag from an {@code If-Match} header, or null when no check is requested. */
    public static String expected(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.trim().equals("*")) {
            return null;
        }
        String tag = ifMatch.trim();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        if (tag.length() < 2 || !tag.startsWith("\"") || !tag.endsWith("\"")) {
            throw new BadRequestException("Malformed If-Match header: " + ifMatch);
        }
        return tag.substring(1, tag.length() - 1);
    }

    public static <T> ResponseEntity<T> ok(Versioned<T> versioned) {
        return ResponseEntity.ok().eTag(of(versioned.revision())).body(versioned.value());
    }

    public static <T> ResponseEntity<T> ok(Tagged<T> tagged) {
        return ResponseEntity.ok().eTag(of(tagged.etag())).body(tagged.value());
    }

    public static <T> ResponseEntity<T> created(URI location, Tagged<T> tagged) {
        return ResponseEntity.created(location).eTag(of(tagged.etag())).body(tagged.value());
    }
}
