package dev.soynx.heim.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Tab;

class MembershipTest {

    private HeimConfig config;
    private Tab t1;
    private Tab t2;

    private static Tab tab(String id, boolean showAll, List<String>... groups) {
        Tab tab = new Tab();
        tab.setId(id);
        tab.setName(id);
        tab.setShowAll(showAll);
        int i = 0;
        for (List<String> ids : groups) {
            Group g = new Group();
            g.setId("grp_" + id + "_" + i++);
            g.setServiceIds(new ArrayList<>(ids));
            tab.getGroups().add(g);
        }
        return tab;
    }

    private static Service service(String id, String name) {
        Service s = new Service();
        s.setId(id);
        s.setName(name);
        return s;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = new HeimConfig();
        config.getServices().addAll(List.of(service("svc_c", "charlie"), service("svc_a", "Alpha"),
                service("svc_b", "bravo"), service("svc_d", "Delta")));
        t1 = tab("tab_1", false, List.of("svc_b"), List.of("svc_a"));
        t2 = tab("tab_2", true, List.of("svc_d"));
        config.getTabs().addAll(List.of(t1, t2));
    }

    @Test
    void tabIdsOfComputedFromGroupsInTabOrder() {
        Membership.setTabs(config, "svc_d", Set.of("tab_2", "tab_1"));
        assertThat(Membership.tabIdsOf(config, "svc_a")).containsExactly("tab_1");
        assertThat(Membership.tabIdsOf(config, "svc_d")).containsExactly("tab_1", "tab_2");
        assertThat(Membership.tabIdsOf(config, "svc_c")).isEmpty();
    }

    @Test
    void setTabsAppendsToFirstGroupAndRemovesFromAllGroups() {
        Membership.setTabs(config, "svc_c", Set.of("tab_1"));
        assertThat(t1.getGroups().get(0).getServiceIds()).containsExactly("svc_b", "svc_c");

        // Already a member via the second group: left where it is.
        Membership.setTabs(config, "svc_a", Set.of("tab_1", "tab_2"));
        assertThat(t1.getGroups().get(0).getServiceIds()).containsExactly("svc_b", "svc_c");
        assertThat(t1.getGroups().get(1).getServiceIds()).containsExactly("svc_a");
        assertThat(t2.getGroups().get(0).getServiceIds()).containsExactly("svc_d", "svc_a");

        Membership.setTabs(config, "svc_a", Set.of());
        assertThat(Membership.tabIdsOf(config, "svc_a")).isEmpty();
    }

    @Test
    void removeEverywhere() {
        t2.getGroups().getFirst().getServiceIds().add("svc_b");
        Membership.removeEverywhere(config, "svc_b");
        assertThat(Membership.tabIdsOf(config, "svc_b")).isEmpty();
        assertThat(t1.getGroups().get(1).getServiceIds()).containsExactly("svc_a");
    }

    @Test
    void resolveNonShowAllUsesGroupOrderOnly() {
        assertThat(Membership.resolveServices(config, t1)).extracting(Service::getId)
                .containsExactly("svc_b", "svc_a");
    }

    @Test
    void resolveShowAllAppendsUnreferencedCaseInsensitiveAlphabetical() {
        assertThat(Membership.resolveServices(config, t2)).extracting(Service::getId)
                .containsExactly("svc_d", "svc_a", "svc_b", "svc_c");
    }

    @Test
    void resolveIgnoresDanglingAndDuplicateReferences() {
        t1.getGroups().get(1).getServiceIds().addAll(List.of("svc_gone", "svc_b"));
        assertThat(Membership.resolveServices(config, t1)).extracting(Service::getId)
                .containsExactly("svc_b", "svc_a");
    }

    @Test
    void sameNameTieBrokenById() {
        config.getServices().add(service("svc_0", "alpha"));
        assertThat(Membership.resolveServices(config, t2)).extracting(Service::getId)
                .containsExactly("svc_d", "svc_0", "svc_a", "svc_b", "svc_c");
    }
}
