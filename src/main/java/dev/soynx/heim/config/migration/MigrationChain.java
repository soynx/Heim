package dev.soynx.heim.config.migration;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Applies all {@link Migration}s needed to bring a config tree up to {@link #CURRENT_VERSION}. */
@Component
public class MigrationChain {

    public static final int CURRENT_VERSION = 1;
    static final String VERSION_FIELD = "schemaVersion";

    private static final Logger log = LoggerFactory.getLogger(MigrationChain.class);

    private final List<Migration> migrations;

    public MigrationChain(List<Migration> migrations) {
        this.migrations = migrations.stream().sorted(Comparator.comparingInt(Migration::fromVersion)).toList();
        for (int i = 0; i < this.migrations.size(); i++) {
            if (this.migrations.get(i).fromVersion() != i) {
                throw new IllegalStateException("Migrations must form a gap-free chain starting at version 0");
            }
        }
        if (this.migrations.size() != CURRENT_VERSION) {
            throw new IllegalStateException("Expected " + CURRENT_VERSION + " migrations, found " + this.migrations.size());
        }
    }

    /** Migrates {@code root} in place. Returns true if anything was applied. */
    public boolean migrate(ObjectNode root) {
        int version = root.path(VERSION_FIELD).asInt(0);
        if (version > CURRENT_VERSION) {
            log.warn("Config schemaVersion {} is newer than supported {}; loading it as-is", version, CURRENT_VERSION);
            return false;
        }
        boolean changed = false;
        while (version < CURRENT_VERSION) {
            log.info("Migrating config from schemaVersion {} to {}", version, version + 1);
            migrations.get(version).migrate(root);
            version++;
            root.put(VERSION_FIELD, version);
            changed = true;
        }
        return changed;
    }
}
