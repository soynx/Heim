package dev.soynx.heim.config;

/** A value together with its {@link ResourceTags resource tag}, sent as HTTP {@code ETag}. */
public record Tagged<T>(T value, String etag) {
}
