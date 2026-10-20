package dev.soynx.heim.config;

import dev.soynx.heim.config.model.HeimConfig;

/**
 * Published synchronously after every successful save. {@code config} is a private copy of the
 * saved state; listeners must treat it as read-only and must not call {@link ConfigStore#update}.
 */
public record ConfigChangedEvent(long revision, HeimConfig config) {
}
