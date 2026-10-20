package dev.soynx.heim.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.config.model.BackgroundRef;
import dev.soynx.heim.config.model.BackgroundSource;
import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.ProbeConfig;
import dev.soynx.heim.config.model.ProbeDefaults;
import dev.soynx.heim.config.model.ProbeMethod;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Settings;
import dev.soynx.heim.config.model.Tab;

/**
 * Enforces the config invariants in place: at least one tab, exactly one default tab, at least one
 * group per tab, globally unique ids, no dangling or duplicate service references within a tab,
 * a valid {@code primaryLinkId}, and no null collections or sub-objects.
 */
public final class ConfigRepair {

    private ConfigRepair() {
    }

    /** Repairs {@code config} in place and returns a description of every fix applied. */
    public static List<String> repair(HeimConfig config) {
        List<String> fixes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        repairSettings(config, fixes);
        repairServices(config, ids, fixes);
        repairTabs(config, ids, fixes);
        return fixes;
    }

    private static void repairSettings(HeimConfig config, List<String> fixes) {
        Settings settings = orDefault(config.getSettings(), Settings::new);
        config.setSettings(settings);
        if (settings.getTitle() == null || settings.getTitle().isBlank()) {
            settings.setTitle(Settings.DEFAULT_TITLE);
        }
        settings.setTheme(orDefault(settings.getTheme(), () -> Settings.DEFAULT_THEME));
        settings.setCustomCss(orDefault(settings.getCustomCss(), () -> ""));
        settings.setAuth(orDefault(settings.getAuth(), AuthSettings::new));

        BackgroundRef background = orDefault(settings.getBackground(), BackgroundRef::new);
        settings.setBackground(background);
        background.setSource(orDefault(background.getSource(), () -> BackgroundSource.NONE));
        if (background.getSource() == BackgroundSource.UPLOAD && background.getCachedFile() == null) {
            background.setSource(BackgroundSource.NONE);
            fixes.add("background: upload without file reset to none");
        }

        ProbeDefaults probe = orDefault(settings.getProbe(), ProbeDefaults::new);
        settings.setProbe(probe);
        if (probe.getIntervalSeconds() < 5) {
            probe.setIntervalSeconds(ProbeDefaults.DEFAULT_INTERVAL_SECONDS);
            fixes.add("probe: invalid default interval reset");
        }
        if (probe.getTimeoutSeconds() < 1) {
            probe.setTimeoutSeconds(ProbeDefaults.DEFAULT_TIMEOUT_SECONDS);
            fixes.add("probe: invalid default timeout reset");
        }
    }

    private static void repairServices(HeimConfig config, Set<String> ids, List<String> fixes) {
        List<Service> services = orDefault(config.getServices(), ArrayList::new);
        services.removeIf(Objects::isNull);
        config.setServices(services);
        for (Service service : services) {
            service.setId(uniqueId(service.getId(), IdGenerator.SERVICE, ids, "service", fixes));
            if (service.getName() == null || service.getName().isBlank()) {
                service.setName("Service");
            }
            IconRef icon = orDefault(service.getIcon(), IconRef::new);
            icon.setSource(orDefault(icon.getSource(), () -> IconSource.FAVICON));
            service.setIcon(icon);

            List<Link> links = orDefault(service.getLinks(), ArrayList::new);
            links.removeIf(Objects::isNull);
            service.setLinks(links);
            for (Link link : links) {
                link.setId(uniqueId(link.getId(), IdGenerator.LINK, ids, "link", fixes));
                link.setUrl(orDefault(link.getUrl(), () -> ""));
                repairProbe(link);
            }
            if (service.findLink(service.getPrimaryLinkId()).isEmpty()) {
                String primary = links.isEmpty() ? null : links.getFirst().getId();
                if (!Objects.equals(primary, service.getPrimaryLinkId())) {
                    service.setPrimaryLinkId(primary);
                    fixes.add("service " + service.getId() + ": primary link set to " + primary);
                }
            }
            if (icon.getFaviconLinkId() != null && service.findLink(icon.getFaviconLinkId()).isEmpty()) {
                icon.setFaviconLinkId(null);
            }
        }
    }

    private static void repairProbe(Link link) {
        ProbeConfig probe = orDefault(link.getProbe(), ProbeConfig::new);
        link.setProbe(probe);
        probe.setMethod(orDefault(probe.getMethod(), () -> ProbeMethod.GET));
        if (probe.getExpectedStatus() == null || !probe.getExpectedStatus().matches(ProbeConfig.EXPECTED_STATUS_PATTERN)) {
            probe.setExpectedStatus(ProbeConfig.DEFAULT_EXPECTED_STATUS);
        }
        if (probe.getIntervalSeconds() != null && probe.getIntervalSeconds() < 5) {
            probe.setIntervalSeconds(null);
        }
        if (probe.getTimeoutSeconds() != null && probe.getTimeoutSeconds() < 1) {
            probe.setTimeoutSeconds(null);
        }
    }

    private static void repairTabs(HeimConfig config, Set<String> ids, List<String> fixes) {
        List<Tab> tabs = orDefault(config.getTabs(), ArrayList::new);
        tabs.removeIf(Objects::isNull);
        config.setTabs(tabs);
        if (tabs.isEmpty()) {
            Tab tab = DefaultConfig.newTab(DefaultConfig.DEFAULT_TAB_NAME);
            tab.setShowAll(true);
            tabs.add(tab);
            fixes.add("no tabs: created default tab");
        }

        Set<String> serviceIds = new HashSet<>();
        config.getServices().forEach(s -> serviceIds.add(s.getId()));

        for (Tab tab : tabs) {
            tab.setId(uniqueId(tab.getId(), IdGenerator.TAB, ids, "tab", fixes));
            if (tab.getName() == null || tab.getName().isBlank()) {
                tab.setName("Tab");
            }
            List<Group> groups = orDefault(tab.getGroups(), ArrayList::new);
            groups.removeIf(Objects::isNull);
            tab.setGroups(groups);
            if (groups.isEmpty()) {
                groups.add(DefaultConfig.newGroup());
            }
            Set<String> seenInTab = new HashSet<>();
            for (Group group : groups) {
                group.setId(uniqueId(group.getId(), IdGenerator.GROUP, ids, "group", fixes));
                List<String> refs = orDefault(group.getServiceIds(), ArrayList::new);
                int before = refs.size();
                refs.removeIf(id -> !serviceIds.contains(id) || !seenInTab.add(id));
                group.setServiceIds(refs);
                if (refs.size() != before) {
                    fixes.add("tab " + tab.getId() + ": removed " + (before - refs.size()) + " dangling/duplicate references");
                }
            }
        }

        List<Tab> defaults = tabs.stream().filter(Tab::isDefault).toList();
        if (defaults.size() != 1) {
            Tab keep = defaults.isEmpty() ? tabs.getFirst() : defaults.getFirst();
            tabs.forEach(t -> t.setDefault(t == keep));
            fixes.add("default tab set to " + keep.getId());
        }
    }

    private static String uniqueId(String id, String prefix, Set<String> ids, String what, List<String> fixes) {
        if (id != null && !id.isBlank() && ids.add(id)) {
            return id;
        }
        String fresh;
        do {
            fresh = IdGenerator.newId(prefix);
        } while (!ids.add(fresh));
        fixes.add(what + " id " + id + " replaced by " + fresh);
        return fresh;
    }

    private static <T> T orDefault(T value, Supplier<T> fallback) {
        return value != null ? value : fallback.get();
    }
}
