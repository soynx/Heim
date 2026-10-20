package dev.soynx.heim.config;

import java.util.ArrayList;

import dev.soynx.heim.config.migration.MigrationChain;
import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Tab;

/** Factories for fresh config entities. */
public final class DefaultConfig {

    public static final String DEFAULT_TAB_NAME = "Dashboard";

    private DefaultConfig() {
    }

    /** First-start config: a single default "Dashboard" tab that shows all services. */
    public static HeimConfig create() {
        HeimConfig config = new HeimConfig();
        config.setSchemaVersion(MigrationChain.CURRENT_VERSION);
        Tab tab = newTab(DEFAULT_TAB_NAME);
        tab.setDefault(true);
        tab.setShowAll(true);
        config.getTabs().add(tab);
        return config;
    }

    /** A tab with a fresh id and one empty, unnamed group. */
    public static Tab newTab(String name) {
        Tab tab = new Tab();
        tab.setId(IdGenerator.tabId());
        tab.setName(name);
        tab.getGroups().add(newGroup());
        return tab;
    }

    public static Group newGroup() {
        Group group = new Group();
        group.setId(IdGenerator.groupId());
        group.setServiceIds(new ArrayList<>());
        return group;
    }
}
