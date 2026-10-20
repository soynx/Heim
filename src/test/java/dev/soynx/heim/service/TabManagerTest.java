package dev.soynx.heim.service;

import static dev.soynx.heim.service.Requests.service;
import static dev.soynx.heim.service.Requests.tab;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.soynx.heim.api.dto.DashboardView;
import dev.soynx.heim.api.dto.DashboardView.DashboardTab;
import dev.soynx.heim.api.dto.GroupRequest;
import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.api.dto.TabRequest;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.TestStores;
import dev.soynx.heim.config.model.Group;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.error.NotFoundException;

class TabManagerTest {

    @TempDir
    Path dir;

    private ConfigStore store;
    private TabManager tabs;
    private ServiceManager services;
    private String home;

    @BeforeEach
    void setUp() {
        store = TestStores.open(dir);
        tabs = new TabManager(store);
        services = new ServiceManager(store);
        home = store.read().getTabs().getFirst().getId();
    }

    private String svc(String name, List<String> tabIds) {
        return services.create(service(name, tabIds), null).value().id();
    }

    private Tab tabById(String id) {
        return store.read().findTab(id).orElseThrow();
    }

    private DashboardTab dashboardTab(String id) {
        return tabs.dashboard().value().tabs().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    // --- CRUD -----------------------------------------------------------------------------

    @Test
    void createAddsNonDefaultTabWithOneGroup() {
        Tab created = tabs.create(tab("  Media  "), null).value();

        assertThat(created.getId()).matches("tab_[0-9a-z]{8}");
        assertThat(created.getName()).isEqualTo("Media");
        assertThat(created.isDefault()).isFalse();
        assertThat(created.isShowAll()).isFalse();
        assertThat(created.getGroups()).singleElement().satisfies(g -> assertThat(g.getServiceIds()).isEmpty());
        assertThat(store.read().getTabs()).extracting(Tab::getId).containsExactly(home, created.getId());
    }

    @Test
    void createOrUpdateWithIsDefaultMovesDefault() {
        String media = tabs.create(tab("Media", true, null), null).value().getId();

        assertThat(store.read().getTabs()).filteredOn(Tab::isDefault).extracting(Tab::getId).containsExactly(media);

        tabs.update(home, tab("Home", true, null), null);
        assertThat(store.read().getTabs()).filteredOn(Tab::isDefault).extracting(Tab::getId).containsExactly(home);

        // isDefault=false never leaves the config without a default.
        tabs.update(home, tab("Home", false, null), null);
        assertThat(store.read().getTabs()).filteredOn(Tab::isDefault).extracting(Tab::getId).containsExactly(home);
    }

    @Test
    void updateShowAllNullKeepsValue() {
        tabs.update(home, tab("Renamed", null, null), null);
        assertThat(tabById(home).isShowAll()).isTrue();
        assertThat(tabById(home).getName()).isEqualTo("Renamed");

        tabs.update(home, tab("Renamed", null, false), null);
        assertThat(tabById(home).isShowAll()).isFalse();
    }

    @Test
    void updateKeepsGroups() {
        String a = svc("A", null);
        tabs.update(home, tab("Renamed"), null);
        assertThat(tabById(home).getGroups().getFirst().getServiceIds()).containsExactly(a);
    }

    @Test
    void unknownTabIsNotFound() {
        assertThatThrownBy(() -> tabs.update("tab_nope0000", tab("X"), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> tabs.delete("tab_nope0000", null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> tabs.setGroups("tab_nope0000", List.of(), null))
                .isInstanceOf(NotFoundException.class);
    }

    // --- delete ---------------------------------------------------------------------------

    @Test
    void deleteLastTabRefused() {
        long rev = store.revision();
        assertThatThrownBy(() -> tabs.delete(home, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("last tab");
        assertThat(store.read().getTabs()).hasSize(1);
        assertThat(store.revision()).isEqualTo(rev);
    }

    @Test
    void deleteDefaultTabPromotesFirstRemaining() {
        String b = tabs.create(tab("B"), null).value().getId();
        String c = tabs.create(tab("C"), null).value().getId();
        tabs.update(c, tab("C", true, null), null);

        tabs.delete(c, null);

        assertThat(store.read().getTabs()).extracting(Tab::getId).containsExactly(home, b);
        assertThat(tabById(home).isDefault()).isTrue();
        assertThat(tabById(b).isDefault()).isFalse();

        tabs.delete(home, null);
        assertThat(store.read().getTabs()).singleElement().satisfies(t -> {
            assertThat(t.getId()).isEqualTo(b);
            assertThat(t.isDefault()).isTrue();
        });
    }

    @Test
    void deleteTabKeepsServices() {
        String b = tabs.create(tab("B"), null).value().getId();
        String a = svc("A", List.of(b));

        tabs.delete(b, null);

        assertThat(services.get(a).value().tabIds()).isEmpty();
        assertThat(store.read().getServices()).hasSize(1);
    }

    // --- order ----------------------------------------------------------------------------

    @Test
    void reorderRequiresEveryTabExactlyOnce() {
        String b = tabs.create(tab("B"), null).value().getId();
        String c = tabs.create(tab("C"), null).value().getId();

        assertThat(tabs.reorder(List.of(c, home, b), null).value()).extracting(Tab::getId)
                .containsExactly(c, home, b);
        assertThat(store.read().getTabs()).extracting(Tab::getId).containsExactly(c, home, b);

        long rev = store.revision();
        assertThatThrownBy(() -> tabs.reorder(List.of(c, home), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> tabs.reorder(List.of(c, home, home), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> tabs.reorder(List.of(c, home, b, b), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> tabs.reorder(List.of(c, home, "tab_nope0000"), null))
                .isInstanceOf(BadRequestException.class);
        assertThat(store.revision()).isEqualTo(rev);
    }

    // --- groups / per-tab ordering --------------------------------------------------------

    @Test
    void perTabOrderingIsIndependent() {
        String media = tabs.create(tab("Media"), null).value().getId();
        String a = svc("A", List.of(home, media));
        String b = svc("B", List.of(home, media));
        String c = svc("C", List.of(home, media));
        String homeGroup = tabById(home).getGroups().getFirst().getId();
        String mediaGroup = tabById(media).getGroups().getFirst().getId();

        tabs.setGroups(media, List.of(new GroupRequest(mediaGroup, null, List.of(c, a, b))), null);

        assertThat(tabById(home).getGroups().getFirst().getServiceIds()).containsExactly(a, b, c);
        assertThat(tabById(media).getGroups().getFirst().getServiceIds()).containsExactly(c, a, b);
        assertThat(tabById(media).getGroups().getFirst().getId()).isEqualTo(mediaGroup);
        assertThat(dashboardTab(home).services()).extracting(ServiceDto::id).containsExactly(a, b, c);
        assertThat(dashboardTab(media).services()).extracting(ServiceDto::id).containsExactly(c, a, b);

        tabs.setGroups(home, List.of(new GroupRequest(homeGroup, null, List.of(b, c, a))), null);
        assertThat(dashboardTab(home).services()).extracting(ServiceDto::id).containsExactly(b, c, a);
        assertThat(dashboardTab(media).services()).extracting(ServiceDto::id).containsExactly(c, a, b);
    }

    @Test
    void setGroupsAssignsIdsDedupesAndRejectsUnknownServices() {
        String a = svc("A", List.of());
        String b = svc("B", List.of());
        String existing = tabById(home).getGroups().getFirst().getId();

        Tab result = tabs.setGroups(home, List.of(
                new GroupRequest(existing, "First", List.of(a, b, a)),
                new GroupRequest(existing, "Second", List.of(b)),
                new GroupRequest("grp_forged00", null, null)), null).value();

        List<Group> groups = result.getGroups();
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).getId()).isEqualTo(existing);
        assertThat(groups.get(0).getName()).isEqualTo("First");
        assertThat(groups.get(0).getServiceIds()).containsExactly(a, b);
        assertThat(groups.get(1).getId()).isNotEqualTo(existing).matches("grp_[0-9a-z]{8}");
        assertThat(groups.get(1).getServiceIds()).isEmpty();
        assertThat(groups.get(2).getId()).isNotEqualTo("grp_forged00");

        long rev = store.revision();
        assertThatThrownBy(() -> tabs.setGroups(home, List.of(new GroupRequest(null, null, List.of("svc_nope0000"))),
                null)).isInstanceOf(BadRequestException.class);
        assertThat(store.revision()).isEqualTo(rev);
    }

    @Test
    void setGroupsEmptyListLeavesOneEmptyGroup() {
        svc("A", null);
        Tab result = tabs.setGroups(home, List.of(), null).value();
        assertThat(result.getGroups()).hasSize(1);
        assertThat(tabById(home).getGroups()).singleElement()
                .satisfies(g -> assertThat(g.getServiceIds()).isEmpty());
    }

    // --- showAll / dashboard --------------------------------------------------------------

    @Test
    void showAllListsReferencedInGroupOrderThenRestAlphabetically() {
        String media = tabs.create(tab("Media", null, true), null).value().getId();
        String zed = svc("zed", List.of());
        String alpha = svc("Alpha", List.of());
        String mid = svc("mid", List.of());
        String referenced2 = svc("Yankee", List.of());
        String referenced1 = svc("Xray", List.of());
        String group = tabById(media).getGroups().getFirst().getId();
        tabs.setGroups(media, List.of(new GroupRequest(group, null, List.of(referenced1)),
                new GroupRequest(null, null, List.of(referenced2))), null);

        assertThat(dashboardTab(media).services()).extracting(ServiceDto::id)
                .containsExactly(referenced1, referenced2, alpha, mid, zed);
    }

    @Test
    void nonShowAllTabListsOnlyReferenced() {
        String media = tabs.create(tab("Media"), null).value().getId();
        svc("A", List.of());
        String b = svc("B", List.of(media));

        assertThat(dashboardTab(media).services()).extracting(ServiceDto::id).containsExactly(b);
        assertThat(dashboardTab(home).services()).hasSize(2);
    }

    @Test
    void dashboardCarriesRevisionAndPublicSettings() {
        svc("A", null);
        DashboardView view = tabs.dashboard().value();

        assertThat(view.revision()).isEqualTo(store.revision());
        assertThat(view.settings().title()).isEqualTo("Heim");
        assertThat(view.settings().customCss()).isFalse();
        assertThat(view.settings().background().url()).isNull();
        assertThat(view.tabs()).singleElement().satisfies(t -> {
            assertThat(t.isDefault()).isTrue();
            assertThat(t.services()).singleElement()
                    .satisfies(s -> assertThat(s.tabIds()).containsExactly(home));
        });
    }

    // --- icon -----------------------------------------------------------------------------

    private static TabRequest tabWithIcon(String name, IconSource source, String value, String cachedFile) {
        IconRef icon = Requests.icon(source, value);
        icon.setCachedFile(cachedFile);
        return new TabRequest(name, icon, null, null, null);
    }

    private void setTabCachedFile(String tabId, String file) {
        store.update(null, c -> {
            c.findTab(tabId).orElseThrow().getIcon().setCachedFile(file);
            return null;
        });
    }

    @Test
    void clientSentTabIconCachedFileIgnored() {
        Tab created = tabs.create(tabWithIcon("Media", IconSource.LIBRARY, "film", "../../etc/passwd"), null).value();
        assertThat(created.getIcon().getCachedFile()).isNull();

        setTabCachedFile(created.getId(), "tab_cached.svg");
        Tab same = tabs.update(created.getId(), tabWithIcon("Media2", IconSource.LIBRARY, "film", "client.png"), null)
                .value();
        assertThat(same.getIcon().getCachedFile()).isEqualTo("tab_cached.svg");

        Tab changed = tabs.update(created.getId(), tabWithIcon("Media2", IconSource.LIBRARY, "music", "client.png"),
                null).value();
        assertThat(changed.getIcon().getCachedFile()).isNull();
        assertThat(tabById(created.getId()).getIcon().getCachedFile()).isNull();

        assertThat(tabs.update(created.getId(), tab("NoIcon"), null).value().getIcon()).isNull();
    }

    @Test
    void tabIconUrlMustBeHttp() {
        assertThatThrownBy(() -> tabs.create(tabWithIcon("X", IconSource.URL, "javascript:alert(1)", null), null))
                .isInstanceOf(BadRequestException.class);
    }
}
