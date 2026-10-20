package dev.soynx.heim.config;

/** A value together with the config revision it was produced from. */
public record Versioned<T>(T value, long revision) {
}
