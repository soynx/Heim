package dev.soynx.heim.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.soynx.heim.api.dto.DashboardView;
import dev.soynx.heim.api.dto.GroupRequest;
import dev.soynx.heim.api.dto.TabRequest;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.DefaultConfig;
import dev.soynx.heim.config.IdGenerator;
import dev.soynx.heim.config.ResourceTags;
import dev.soynx.heim.config.Tagged;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.error.NotFoundException;

/** Tab CRUD, ordering, groups and the resolved dashboard view. */
@Component
public class TabManager {

    private final ConfigStore store;

    public TabManager(ConfigStore store) {
        this.store = store;
    }

    public Versioned<DashboardView> dashboard() {
        HeimConfig config = store.read();
        return new Versioned<>(DashboardView.from(config), config.getRevision());
    }

    public Tagged<List<Tab>> list() {
        HeimConfig config = store.read();
        return new Tagged<>(config.getTabs(), orderTag(config));
    }

    public Tagged<Tab> get(String id) {
        return tagged(findTab(store.read(), id));
    }

    /** {@code expectedTag} is checked against the tab order tag (see {@link #orderTag}). */
    public Tagged<Tab> create(TabRequest request, String expectedTag) {
        return tagged(store.update(null, config -> {
            ResourceTags.check(expectedTag, orderTag(config), "Tab list");
            Tab tab = DefaultConfig.newTab(request.name().trim());
            config.getTabs().add(tab);
            apply(config, tab, request);
            return tab;
        }).value());
    }

    public Tagged<Tab> update(String id, TabRequest request, String expectedTag) {
        return tagged(store.update(null, config -> {
            Tab tab = findTab(config, id, expectedTag);
            tab.setName(request.name().trim());
            apply(config, tab, request);
            return tab;
        }).value());
    }

    /** Refuses to delete the last tab; deleting the default tab promotes the first remaining one. */
    public Versioned<Void> delete(String id, String expectedTag) {
        return store.update(null, config -> {
            Tab tab = findTab(config, id, expectedTag);
            if (config.getTabs().size() == 1) {
                throw new BadRequestException("Cannot delete the last tab");
            }
            config.getTabs().remove(tab);
            if (tab.isDefault()) {
                config.getTabs().getFirst().setDefault(true);
            }
            return null;
        });
    }

    /** {@code tabIds} must contain every tab exactly once; {@code expectedTag} is the tab order tag. */
    public Tagged<List<Tab>> reorder(List<String> tabIds, String expectedTag) {
        return store.update(null, config -> {
            ResourceTags.check(expectedTag, orderTag(config), "Tab order");
            Map<String, Tab> byId = config.getTabs().stream().collect(Collectors.toMap(Tab::getId, Function.identity()));
            if (tabIds.size() != byId.size() || !byId.keySet().equals(new HashSet<>(tabIds))) {
                throw new BadRequestException("tabIds must list every tab exactly once");
            }
            config.setTabs(new ArrayList<>(tabIds.stream().map(byId::get).toList()));
            return new Tagged<>(config.getTabs(), orderTag(config));
        }).value();
    }

    /**
     * Replaces a tab's groups and their service order. Unknown service ids are rejected; a service
     * listed more than once keeps its first position.
     */
    public Tagged<Tab> setGroups(String id, List<GroupRequest> groups, String expectedTag) {
        return tagged(store.update(null, config -> {
            Tab tab = findTab(config, id, expectedTag);
            Set<String> existingGroupIds = tab.getGroups().stream().map(Group::getId).collect(Collectors.toSet());
            Set<String> seenServices = new HashSet<>();
            Set<String> usedGroupIds = new HashSet<>();
            List<Group> result = new ArrayList<>();
            for (GroupRequest request : groups) {
                Group group = new Group();
                boolean keepId = request.id() != null && existingGroupIds.contains(request.id())
                        && usedGroupIds.add(request.id());
                group.setId(keepId ? request.id() : IdGenerator.groupId());
                group.setName(request.name());
                Set<String> serviceIds = new LinkedHashSet<>();
                for (String serviceId : request.serviceIds() != null ? request.serviceIds() : List.<String>of()) {
                    if (config.findService(serviceId).isEmpty()) {
                        throw new BadRequestException("Unknown service: " + serviceId);
                    }
                    if (seenServices.add(serviceId)) {
                        serviceIds.add(serviceId);
                    }
                }
                group.setServiceIds(new ArrayList<>(serviceIds));
                result.add(group);
            }
            tab.setGroups(result);
            return tab;
        }).value());
    }

    /** Tag of a tab: its content without the server-managed {@code icon.cachedFile}. */
    static String tagOf(Tab tab) {
        return ResourceTags.of(tab, "icon.cachedFile");
    }

    /**
     * Tag of the tab list: the ordered tab ids. Guards reorder and create against a concurrent
     * reorder, create or delete, but not against edits inside a tab (those have their own tags).
     */
    static String orderTag(HeimConfig config) {
        return ResourceTags.of(config.getTabs().stream().map(Tab::getId).toList());
    }

    private static Tagged<Tab> tagged(Tab tab) {
        return new Tagged<>(tab, tagOf(tab));
    }

    private static void apply(HeimConfig config, Tab tab, TabRequest request) {
        tab.setIcon(request.icon() != null ? ServiceManager.icon(request.icon(), tab.getIcon(), Map.of()) : null);
        tab.setColor(request.color());
        if (request.showAll() != null) {
            tab.setShowAll(request.showAll());
        }
        if (Boolean.TRUE.equals(request.isDefault())) {
            config.getTabs().forEach(t -> t.setDefault(t == tab));
        }
    }

    private static Tab findTab(HeimConfig config, String id) {
        return config.findTab(id).orElseThrow(() -> NotFoundException.tab(id));
    }

    /** Finds the tab and checks it still has {@code expectedTag}; for use inside an update mutator. */
    private static Tab findTab(HeimConfig config, String id, String expectedTag) {
        Tab tab = findTab(config, id);
        ResourceTags.check(expectedTag, tagOf(tab), "Tab " + id);
        return tab;
    }
}
