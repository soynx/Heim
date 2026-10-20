package dev.soynx.heim.config.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Root of {@code config.json}. */
public class HeimConfig extends Extensible {

    private int schemaVersion;
    /** Incremented on every save; exposed to clients as the ETag. */
    private long revision;
    private Settings settings = new Settings();
    private List<Tab> tabs = new ArrayList<>();
    private List<Service> services = new ArrayList<>();

    public Optional<Service> findService(String serviceId) {
        return services.stream().filter(s -> serviceId != null && serviceId.equals(s.getId())).findFirst();
    }

    public Optional<Tab> findTab(String tabId) {
        return tabs.stream().filter(t -> tabId != null && tabId.equals(t.getId())).findFirst();
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    public List<Tab> getTabs() {
        return tabs;
    }

    public void setTabs(List<Tab> tabs) {
        this.tabs = tabs;
    }

    public List<Service> getServices() {
        return services;
    }

    public void setServices(List<Service> services) {
        this.services = services;
    }
}
