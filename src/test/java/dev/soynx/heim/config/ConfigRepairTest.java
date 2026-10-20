package dev.soynx.heim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.ProbeMethod;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Tab;

class ConfigRepairTest {

    private static Tab tab(String id, boolean isDefault, String... serviceIds) {
        Tab tab = new Tab();
        tab.setId(id);
        tab.setName(id);
        tab.setDefault(isDefault);
        Group group = new Group();
        group.setId("grp_" + id);
        group.setServiceIds(new ArrayList<>(List.of(serviceIds)));
        tab.getGroups().add(group);
        return tab;
    }

    private static Service service(String id, String... linkIds) {
        Service service = new Service();
        service.setId(id);
        service.setName(id);
        for (String linkId : linkIds) {
            Link link = new Link();
            link.setId(linkId);
            link.setUrl("http://" + linkId);
            service.getLinks().add(link);
        }
        return service;
    }

    @Test
    void validConfigNeedsNoFixes() {
        HeimConfig config = DefaultConfig.create();
        config.getServices().add(service("svc_a", "lnk_a"));
        config.getServices().getFirst().setPrimaryLinkId("lnk_a");
        config.getTabs().getFirst().getGroups().getFirst().getServiceIds().add("svc_a");

        assertThat(ConfigRepair.repair(config)).isEmpty();
    }

    @Test
    void noTabsCreatesDefaultShowAllTab() {
        HeimConfig config = new HeimConfig();
        config.setTabs(null);

        assertThat(ConfigRepair.repair(config)).isNotEmpty();

        assertThat(config.getTabs()).singleElement().satisfies(t -> {
            assertThat(t.isDefault()).isTrue();
            assertThat(t.isShowAll()).isTrue();
            assertThat(t.getName()).isEqualTo("Dashboard");
            assertThat(t.getGroups()).hasSize(1);
        });
    }

    @Test
    void zeroDefaultsMakesFirstTabDefault() {
        HeimConfig config = new HeimConfig();
        config.getTabs().addAll(List.of(tab("tab_a", false), tab("tab_b", false)));

        ConfigRepair.repair(config);

        assertThat(config.getTabs()).extracting(Tab::isDefault).containsExactly(true, false);
    }

    @Test
    void multipleDefaultsKeepsOnlyFirstDefault() {
        HeimConfig config = new HeimConfig();
        config.getTabs().addAll(List.of(tab("tab_a", false), tab("tab_b", true), tab("tab_c", true)));

        ConfigRepair.repair(config);

        assertThat(config.getTabs()).extracting(Tab::isDefault).containsExactly(false, true, false);
    }

    @Test
    void tabWithoutGroupsGetsOne() {
        HeimConfig config = new HeimConfig();
        Tab tab = tab("tab_a", true);
        tab.setGroups(null);
        Tab empty = tab("tab_b", false);
        empty.getGroups().clear();
        config.getTabs().addAll(List.of(tab, empty));

        ConfigRepair.repair(config);

        assertThat(config.getTabs()).allSatisfy(t -> assertThat(t.getGroups()).singleElement()
                .satisfies(g -> {
                    assertThat(g.getId()).startsWith("grp_");
                    assertThat(g.getServiceIds()).isEmpty();
                }));
    }

    @Test
    void danglingAndDuplicateServiceRefsRemoved() {
        HeimConfig config = new HeimConfig();
        config.getServices().addAll(List.of(service("svc_a"), service("svc_b")));
        Tab tab = tab("tab_a", true, "svc_a", "svc_gone", "svc_b", "svc_a");
        Group second = new Group();
        second.setId("grp_second");
        second.setServiceIds(new ArrayList<>(List.of("svc_b", "svc_gone2")));
        second.getServiceIds().add(null);
        tab.getGroups().add(second);
        config.getTabs().add(tab);

        ConfigRepair.repair(config);

        assertThat(tab.getGroups().get(0).getServiceIds()).containsExactly("svc_a", "svc_b");
        assertThat(tab.getGroups().get(1).getServiceIds()).isEmpty();
    }

    @Test
    void sameServiceMayAppearOnDifferentTabs() {
        HeimConfig config = new HeimConfig();
        config.getServices().add(service("svc_a"));
        config.getTabs().addAll(List.of(tab("tab_a", true, "svc_a"), tab("tab_b", false, "svc_a")));

        ConfigRepair.repair(config);

        assertThat(config.getTabs()).allSatisfy(t ->
                assertThat(t.getGroups().getFirst().getServiceIds()).containsExactly("svc_a"));
    }

    @Test
    void duplicateAndMissingIdsReplacedWithFreshOnes() {
        HeimConfig config = new HeimConfig();
        Service first = service("svc_dup", "lnk_dup");
        Service second = service("svc_dup", "lnk_dup", "lnk_x");
        Service noId = service(null, "lnk_y");
        config.getServices().addAll(List.of(first, second, noId));
        Tab t1 = tab("tab_dup", true);
        Tab t2 = tab("tab_dup", false);
        t2.getGroups().getFirst().setId("grp_tab_dup"); // same group id as t1
        config.getTabs().addAll(List.of(t1, t2));

        ConfigRepair.repair(config);

        List<String> all = new ArrayList<>();
        config.getServices().forEach(s -> {
            all.add(s.getId());
            s.getLinks().forEach(l -> all.add(l.getId()));
        });
        config.getTabs().forEach(t -> {
            all.add(t.getId());
            t.getGroups().forEach(g -> all.add(g.getId()));
        });
        assertThat(all).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(first.getId()).isEqualTo("svc_dup");
        assertThat(second.getId()).matches("svc_[0-9a-z]{8}");
        assertThat(noId.getId()).matches("svc_[0-9a-z]{8}");
        assertThat(second.getLinks().getFirst().getId()).matches("lnk_[0-9a-z]{8}");
        assertThat(t1.getId()).isEqualTo("tab_dup");
        assertThat(t2.getId()).matches("tab_[0-9a-z]{8}");
        assertThat(t2.getGroups().getFirst().getId()).matches("grp_[0-9a-z]{8}");
    }

    @Test
    void invalidPrimaryLinkFallsBackToFirstLink() {
        HeimConfig config = new HeimConfig();
        Service service = service("svc_a", "lnk_1", "lnk_2");
        service.setPrimaryLinkId("lnk_missing");
        Service noPrimary = service("svc_b", "lnk_3");
        Service noLinks = service("svc_c");
        noLinks.setPrimaryLinkId("lnk_ghost");
        config.getServices().addAll(List.of(service, noPrimary, noLinks));

        ConfigRepair.repair(config);

        assertThat(service.getPrimaryLinkId()).isEqualTo("lnk_1");
        assertThat(noPrimary.getPrimaryLinkId()).isEqualTo("lnk_3");
        assertThat(noLinks.getPrimaryLinkId()).isNull();
    }

    @Test
    void validPrimaryLinkKept() {
        HeimConfig config = new HeimConfig();
        Service service = service("svc_a", "lnk_1", "lnk_2");
        service.setPrimaryLinkId("lnk_2");
        config.getServices().add(service);

        ConfigRepair.repair(config);

        assertThat(service.getPrimaryLinkId()).isEqualTo("lnk_2");
    }

    @Test
    void nullLinkProbeIsDefaultedAndInvalidValuesReset() {
        HeimConfig config = new HeimConfig();
        Service service = service("svc_a", "lnk_1", "lnk_2");
        Link nullProbe = service.getLinks().get(0);
        nullProbe.setProbe(null);
        Link badProbe = service.getLinks().get(1);
        badProbe.getProbe().setMethod(null);
        badProbe.getProbe().setExpectedStatus("ok");
        badProbe.getProbe().setIntervalSeconds(1);
        badProbe.getProbe().setTimeoutSeconds(0);
        config.getServices().add(service);

        ConfigRepair.repair(config);

        assertThat(nullProbe.getProbe()).isNotNull();
        assertThat(nullProbe.getProbe().isEnabled()).isFalse();
        assertThat(nullProbe.getProbe().getMethod()).isEqualTo(ProbeMethod.GET);
        assertThat(nullProbe.getProbe().getExpectedStatus()).isEqualTo("200-399");
        assertThat(badProbe.getProbe().getMethod()).isEqualTo(ProbeMethod.GET);
        assertThat(badProbe.getProbe().getExpectedStatus()).isEqualTo("200-399");
        assertThat(badProbe.getProbe().getIntervalSeconds()).isNull();
        assertThat(badProbe.getProbe().getTimeoutSeconds()).isNull();
    }

    @Test
    void nullSubObjectsAndCollectionsAreFilledIn() {
        HeimConfig config = new HeimConfig();
        config.setSettings(null);
        config.setServices(null);
        Service service = service("svc_a");
        service.setName(" ");
        service.setIcon(null);
        service.setLinks(null);

        ConfigRepair.repair(config);
        assertThat(config.getSettings()).isNotNull();
        assertThat(config.getSettings().getTitle()).isEqualTo("Heim");
        assertThat(config.getSettings().getAuth()).isNotNull();
        assertThat(config.getSettings().getBackground()).isNotNull();
        assertThat(config.getSettings().getProbe().getIntervalSeconds()).isEqualTo(60);
        assertThat(config.getServices()).isEmpty();

        config.getServices().add(service);
        ConfigRepair.repair(config);
        assertThat(service.getName()).isEqualTo("Service");
        assertThat(service.getIcon()).isNotNull();
        assertThat(service.getLinks()).isEmpty();
    }

    @Test
    void danglingFaviconLinkIdCleared() {
        HeimConfig config = new HeimConfig();
        Service service = service("svc_a", "lnk_1");
        service.getIcon().setFaviconLinkId("lnk_gone");
        config.getServices().add(service);

        ConfigRepair.repair(config);

        assertThat(service.getIcon().getFaviconLinkId()).isNull();
    }
}
