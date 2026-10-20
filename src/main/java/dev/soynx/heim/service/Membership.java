package dev.soynx.heim.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Tab;

/**
 * Tab membership rules. {@code tab.groups[].serviceIds} is the single source of truth; a
 * service's tab list is always computed from it.
 */
public final class Membership {

    private static final Comparator<Service> ALPHABETICAL =
            Comparator.comparing(Service::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Service::getId);

    private Membership() {
    }

    public static boolean references(Tab tab, String serviceId) {
        return tab.getGroups().stream().anyMatch(g -> g.getServiceIds().contains(serviceId));
    }

    /** Ids of the tabs whose groups reference the service, in tab order. */
    public static List<String> tabIdsOf(HeimConfig config, String serviceId) {
        return config.getTabs().stream().filter(t -> references(t, serviceId)).map(Tab::getId).toList();
    }

    /**
     * Makes the service a member of exactly {@code tabIds}: appended to the first group of tabs it
     * is new to, removed from all groups of tabs it is no longer in, untouched elsewhere.
     */
    public static void setTabs(HeimConfig config, String serviceId, Collection<String> tabIds) {
        Set<String> wanted = new HashSet<>(tabIds);
        for (Tab tab : config.getTabs()) {
            boolean member = references(tab, serviceId);
            if (wanted.contains(tab.getId()) && !member) {
                tab.getGroups().getFirst().getServiceIds().add(serviceId);
            } else if (!wanted.contains(tab.getId()) && member) {
                tab.getGroups().forEach(g -> g.getServiceIds().remove(serviceId));
            }
        }
    }

    public static void removeEverywhere(HeimConfig config, String serviceId) {
        config.getTabs().forEach(t -> t.getGroups().forEach(g -> g.getServiceIds().remove(serviceId)));
    }

    /**
     * Services shown on a tab: referenced ones in group order, then, for {@code showAll} tabs,
     * all unreferenced services alphabetically.
     */
    public static List<Service> resolveServices(HeimConfig config, Tab tab) {
        Map<String, Service> byId = new LinkedHashMap<>();
        config.getServices().forEach(s -> byId.put(s.getId(), s));

        List<Service> result = new ArrayList<>();
        for (Group group : tab.getGroups()) {
            for (String id : group.getServiceIds()) {
                Service service = byId.remove(id);
                if (service != null) {
                    result.add(service);
                }
            }
        }
        if (tab.isShowAll()) {
            byId.values().stream().sorted(ALPHABETICAL).forEach(result::add);
        }
        return result;
    }
}
