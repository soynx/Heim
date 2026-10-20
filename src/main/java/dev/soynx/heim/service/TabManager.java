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

    public Versioned<List<Tab>> list() {
        HeimConfig config = store.read();
        return new Versioned<>(config.getTabs(), config.getRevision());
    }

    public Versioned<Tab> create(TabRequest request, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            Tab tab = DefaultConfig.newTab(request.name().trim());
            config.getTabs().add(tab);
            apply(config, tab, request);
            return tab;
        });
    }

    public Versioned<Tab> update(String id, TabRequest request, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            Tab tab = findTab(config, id);
            tab.setName(request.name().trim());
            apply(config, tab, request);
            return tab;
        });
    }

    /** Refuses to delete the last tab; deleting the default tab promotes the first remaining one. */
    public Versioned<Void> delete(String id, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            Tab tab = findTab(config, id);
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

    /** {@code tabIds} must contain every tab exactly once. */
    public Versioned<List<Tab>> reorder(List<String> tabIds, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            Map<String, Tab> byId = config.getTabs().stream().collect(Collectors.toMap(Tab::getId, Function.identity()));
            if (tabIds.size() != byId.size() || !byId.keySet().equals(new HashSet<>(tabIds))) {
                throw new BadRequestException("tabIds must list every tab exactly once");
            }
            config.setTabs(new ArrayList<>(tabIds.stream().map(byId::get).toList()));
            return config.getTabs();
        });
    }

    /**
     * Replaces a tab's groups and their service order. Unknown service ids are rejected; a service
     * listed more than once keeps its first position.
     */
    public Versioned<Tab> setGroups(String id, List<GroupRequest> groups, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            Tab tab = findTab(config, id);
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
        });
    }

    private static void apply(HeimConfig config, Tab tab, TabRequest request) {
        tab.setIcon(request.icon());
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
}
