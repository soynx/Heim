package dev.soynx.heim.service;

import java.net.URI;
import java.net.URISyntaxException;

import dev.soynx.heim.error.BadRequestException;

/** URL checks shared by the managers. */
final class Urls {

    private Urls() {
    }

    /** Any absolute URI (http, https, smb, ...); links are opened by the browser, not by us. */
    static String requireAbsolute(String value, String field) {
        URI uri = parse(value, field);
        if (uri.getScheme() == null) {
            throw new BadRequestException(field + " must be an absolute URL");
        }
        return value.trim();
    }

    /** An absolute http(s) URL the server itself will fetch. */
    static String requireHttp(String value, String field) {
        URI uri = parse(value, field);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) || uri.getHost() == null) {
            throw new BadRequestException(field + " must be an http(s) URL");
        }
        return value.trim();
    }

    private static URI parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        try {
            return new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException(field + " is not a valid URL");
        }
    }
}
