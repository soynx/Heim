package dev.soynx.heim.config.migration;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Upgrades the raw config JSON from {@link #fromVersion()} to {@code fromVersion() + 1}.
 * Migrations work on the JSON tree so they can handle shapes the current model no longer binds.
 */
public interface Migration {

    int fromVersion();

    void migrate(ObjectNode root);
}
