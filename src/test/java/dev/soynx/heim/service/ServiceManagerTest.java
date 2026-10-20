package dev.soynx.heim.service;

import static dev.soynx.heim.service.Requests.icon;
import static dev.soynx.heim.service.Requests.link;
import static dev.soynx.heim.service.Requests.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.soynx.heim.api.dto.LinkRequest;
import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.api.dto.ServiceRequest;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.TestStores;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.LinkKind;
import dev.soynx.heim.config.model.ProbeConfig;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.error.NotFoundException;

class ServiceManagerTest {

    @TempDir
    Path dir;

    private ConfigStore store;
    private ServiceManager services;
    private TabManager tabs;
    private String defaultTab;

    @BeforeEach
    void setUp() {
        store = TestStores.open(dir);
        services = new ServiceManager(store);
        tabs = new TabManager(store);
        defaultTab = store.read().getTabs().getFirst().getId();
    }

    private String newTab(String name) {
        return tabs.create(Requests.tab(name), null).value().getId();
    }

    private List<String> groupOf(String tabId) {
        return store.read().findTab(tabId).orElseThrow().getGroups().getFirst().getServiceIds();
    }

    // --- tabIds semantics ----------------------------------------------------------------

    @Test
    void createWithNullTabIdsJoinsDefaultTab() {
        String other = newTab("Other");
        ServiceDto dto = services.create(service("Proxmox", null, link(null, "https://pve.lan")), null).value();

        assertThat(dto.id()).matches("svc_[0-9a-z]{8}");
        assertThat(dto.tabIds()).containsExactly(defaultTab);
        assertThat(groupOf(defaultTab)).containsExactly(dto.id());
        assertThat(groupOf(other)).isEmpty();
        assertThat(dto.createdAt()).isNotNull();
        assertThat(dto.updatedAt()).isNotNull();
    }

    @Test
    void createWithEmptyTabIdsJoinsNoTab() {
        ServiceDto dto = services.create(service("Hidden", List.of()), null).value();

        assertThat(dto.tabIds()).isEmpty();
        assertThat(groupOf(defaultTab)).isEmpty();
        assertThat(store.read().getServices()).hasSize(1);
    }

    @Test
    void createWithExplicitTabs() {
        String other = newTab("Other");
        ServiceDto dto = services.create(service("Both", List.of(other, defaultTab)), null).value();

        // tabIds are reported in tab order, not request order.
        assertThat(dto.tabIds()).containsExactly(defaultTab, other);
    }

    @Test
    void unknownTabIsBadRequestAndNothingSaved() {
        long rev = store.revision();
        assertThatThrownBy(() -> services.create(service("X", List.of("tab_nope0000")), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("tab_nope0000");
        assertThat(store.revision()).isEqualTo(rev);
        assertThat(store.read().getServices()).isEmpty();

        String id = services.create(service("Y", null), null).value().id();
        long rev2 = store.revision();
        assertThatThrownBy(() -> services.update(id, service("Y", List.of("tab_nope0000")), null))
                .isInstanceOf(BadRequestException.class);
        assertThat(store.revision()).isEqualTo(rev2);
    }

    @Test
    void updateWithNullTabIdsKeepsMembership() {
        String other = newTab("Other");
        String id = services.create(service("S", List.of(other)), null).value().id();

        ServiceDto updated = services.update(id, service("S2", null), null).value();

        assertThat(updated.name()).isEqualTo("S2");
        assertThat(updated.tabIds()).containsExactly(other);
    }

    @Test
    void updateWithEmptyTabIdsRemovesFromAllTabs() {
        String other = newTab("Other");
        String id = services.create(service("S", List.of(defaultTab, other)), null).value().id();

        ServiceDto updated = services.update(id, service("S", List.of()), null).value();

        assertThat(updated.tabIds()).isEmpty();
        assertThat(groupOf(defaultTab)).isEmpty();
        assertThat(groupOf(other)).isEmpty();
    }

    @Test
    void updateTabIdsAppendsToNewTabAndKeepsPositionInExisting() {
        String other = newTab("Other");
        String a = services.create(service("A", null), null).value().id();
        String b = services.create(service("B", null), null).value().id();
        String c = services.create(service("C", List.of(other)), null).value().id();

        services.update(a, service("A", List.of(defaultTab, other)), null);

        assertThat(groupOf(defaultTab)).containsExactly(a, b);
        assertThat(groupOf(other)).containsExactly(c, a);
    }

    @Test
    void updateUnknownServiceIsNotFound() {
        assertThatThrownBy(() -> services.update("svc_missing0", service("X", null), null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> services.get("svc_missing0")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> services.delete("svc_missing0", null)).isInstanceOf(NotFoundException.class);
    }

    // --- delete ---------------------------------------------------------------------------

    @Test
    void deleteCascadesToAllTabs() {
        String other = newTab("Other");
        String a = services.create(service("A", List.of(defaultTab, other)), null).value().id();
        String b = services.create(service("B", List.of(defaultTab, other)), null).value().id();

        services.delete(a, null);

        assertThat(store.read().getServices()).extracting(s -> s.getId()).containsExactly(b);
        assertThat(groupOf(defaultTab)).containsExactly(b);
        assertThat(groupOf(other)).containsExactly(b);
    }

    // --- links ----------------------------------------------------------------------------

    @Test
    void linkIdsArePreservedForKnownIdsAndFreshOtherwise() {
        ServiceDto created = services.create(service("S", null,
                link(null, "https://a.lan"), link(null, "https://b.lan")), null).value();
        String a = created.links().get(0).getId();
        String b = created.links().get(1).getId();
        assertThat(a).matches("lnk_[0-9a-z]{8}");
        assertThat(b).matches("lnk_[0-9a-z]{8}").isNotEqualTo(a);

        ServiceDto updated = services.update(created.id(), service("S", null,
                link(b, "https://b2.lan"), link("lnk_forged00", "https://c.lan"), link(a, "https://a.lan")), null)
                .value();

        assertThat(updated.links()).extracting(Link::getUrl)
                .containsExactly("https://b2.lan", "https://c.lan", "https://a.lan");
        assertThat(updated.links().get(0).getId()).isEqualTo(b);
        assertThat(updated.links().get(1).getId()).isNotEqualTo("lnk_forged00").matches("lnk_[0-9a-z]{8}");
        assertThat(updated.links().get(2).getId()).isEqualTo(a);
    }

    @Test
    void duplicateLinkIdInRequestOnlyKeepsFirst() {
        ServiceDto created = services.create(service("S", null, link(null, "https://a.lan")), null).value();
        String a = created.links().getFirst().getId();

        ServiceDto updated = services.update(created.id(),
                service("S", null, link(a, "https://one.lan"), link(a, "https://two.lan")), null).value();

        assertThat(updated.links()).extracting(Link::getId).doesNotHaveDuplicates();
        assertThat(updated.links().getFirst().getId()).isEqualTo(a);
    }

    @Test
    void linkIdsCannotBeStolenFromOtherServices() {
        ServiceDto first = services.create(service("A", null, link(null, "https://a.lan")), null).value();
        String foreign = first.links().getFirst().getId();

        ServiceDto second = services.create(service("B", null, link(foreign, "https://b.lan")), null).value();

        assertThat(second.links().getFirst().getId()).isNotEqualTo(foreign);
    }

    @Test
    void clientTempIdUsableAsPrimaryLinkId() {
        ServiceDto dto = services.create(service("S", "tmp-2", null, null,
                link("tmp-1", "https://a.lan"), link("tmp-2", "https://b.lan")), null).value();

        Link second = dto.links().get(1);
        assertThat(second.getId()).matches("lnk_[0-9a-z]{8}");
        assertThat(dto.primaryLinkId()).isEqualTo(second.getId());
        assertThat(store.read().findService(dto.id()).orElseThrow().getPrimaryLinkId()).isEqualTo(second.getId());
    }

    @Test
    void clientTempIdUsableAsFaviconLinkId() {
        IconRef icon = icon(IconSource.FAVICON, null);
        icon.setFaviconLinkId("tmp-2");
        ServiceDto dto = services.create(service("S", null, icon, null,
                link("tmp-1", "https://a.lan"), link("tmp-2", "https://b.lan")), null).value();

        assertThat(dto.icon().getFaviconLinkId()).isEqualTo(dto.links().get(1).getId());
    }

    @Test
    void unknownPrimaryLinkFallsBackToFirstLink() {
        ServiceDto dto = services.create(service("S", "nope", null, null,
                link(null, "https://a.lan"), link(null, "https://b.lan")), null).value();
        assertThat(dto.primaryLinkId()).isEqualTo(dto.links().getFirst().getId());

        ServiceDto noLinks = services.create(service("Empty", null), null).value();
        assertThat(noLinks.primaryLinkId()).isNull();
        assertThat(noLinks.links()).isEmpty();
    }

    @Test
    void linkFieldsAppliedWithDefaults() {
        ProbeConfig probe = new ProbeConfig();
        probe.setEnabled(true);
        probe.setUrl("  ");
        ServiceRequest request = new ServiceRequest("  Trim me  ", "desc", "#fff", null, null,
                List.of(new LinkRequest(null, "LAN", LinkKind.IP, " smb://nas/share ", false, probe),
                        new LinkRequest(null, null, null, "https://x.lan", null, null)),
                null);

        ServiceDto dto = services.create(request, null).value();

        assertThat(dto.name()).isEqualTo("Trim me");
        Link first = dto.links().get(0);
        assertThat(first.getUrl()).isEqualTo("smb://nas/share");
        assertThat(first.getKind()).isEqualTo(LinkKind.IP);
        assertThat(first.isOpenInNewTab()).isFalse();
        assertThat(first.getProbe().isEnabled()).isTrue();
        assertThat(first.getProbe().getUrl()).isNull();
        Link second = dto.links().get(1);
        assertThat(second.isOpenInNewTab()).isTrue();
        assertThat(second.getProbe()).isNotNull();
        assertThat(second.getProbe().isEnabled()).isFalse();
    }

    @Test
    void invalidUrlsRejected() {
        assertThatThrownBy(() -> services.create(service("S", null, link(null, "not a url")), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("links[0].url");
        assertThatThrownBy(() -> services.create(service("S", null, link(null, "relative/path")), null))
                .isInstanceOf(BadRequestException.class);

        ProbeConfig probe = new ProbeConfig();
        probe.setUrl("ftp://host/x");
        ServiceRequest badProbe = new ServiceRequest("S", null, null, null, null,
                List.of(new LinkRequest(null, null, null, "https://a.lan", null, probe)), null);
        assertThatThrownBy(() -> services.create(badProbe, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("probe.url");
        assertThat(store.read().getServices()).isEmpty();
    }

    @Test
    void scriptUrlsRejectedForLinks() {
        assertThatThrownBy(() -> services.create(service("S", null, link(null, "javascript:alert(1)")), null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> services.create(service("S", null,
                link(null, "data:text/html,<script>alert(1)</script>")), null))
                .isInstanceOf(BadRequestException.class);
        for (String url : List.of("JaVaScRiPt:alert(1)", " javascript:alert(1)", "\u0001\tjavascript:alert(1)",
                "java\tscript:alert(1)", "vbscript:msgbox(1)", "VBScript:msgbox(1)", "file:///etc/passwd")) {
            assertThatThrownBy(() -> services.create(service("S", null, link(null, url)), null))
                    .as(url).isInstanceOf(BadRequestException.class);
        }
        assertThat(store.read().getServices()).isEmpty();
    }

    @Test
    void nonWebLinkSchemesAllowed() {
        for (String url : List.of("ssh://root@nas.lan", "rdp://win.lan", "vnc://pi.lan:5900", "smb://nas/share",
                "ftp://nas.lan", "sftp://nas.lan", "steam://run/570")) {
            assertThat(services.create(service("S", null, link(null, url)), null).value().links().getFirst().getUrl())
                    .isEqualTo(url);
        }
    }

    @Test
    void urlAndLibraryIconsValidated() {
        assertThatThrownBy(() -> services.create(service("S", null, icon(IconSource.URL, "file:///etc/passwd"),
                null), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> services.create(service("S", null, icon(IconSource.LIBRARY, " "), null), null))
                .isInstanceOf(BadRequestException.class);
    }

    // --- icon cache -----------------------------------------------------------------------

    private void setCachedFile(String serviceId, String file) {
        store.update(null, c -> {
            c.findService(serviceId).orElseThrow().getIcon().setCachedFile(file);
            return null;
        });
    }

    @Test
    void cachedFileKeptWhenIconUnchanged() {
        IconRef lib = icon(IconSource.LIBRARY, "proxmox");
        String id = services.create(service("S", null, lib, null, link(null, "https://a.lan")), null).value().id();
        setCachedFile(id, "svc_cached.svg");

        IconRef same = icon(IconSource.LIBRARY, "proxmox");
        same.setCachedFile("client-sent.png"); // ignored
        ServiceDto dto = services.update(id, service("Renamed", null, same, null, link(null, "https://a.lan")), null)
                .value();

        assertThat(dto.icon().getCachedFile()).isEqualTo("svc_cached.svg");
    }

    @Test
    void cachedFileClearedWhenIconChanges() {
        String id = services.create(service("S", null, icon(IconSource.LIBRARY, "proxmox"), null), null).value().id();

        setCachedFile(id, "svc_cached.svg");
        IconRef otherValue = icon(IconSource.LIBRARY, "jellyfin");
        otherValue.setCachedFile("client-sent.png");
        assertThat(services.update(id, service("S", null, otherValue, null), null).value().icon().getCachedFile())
                .isNull();

        setCachedFile(id, "svc_cached.svg");
        assertThat(services.update(id, service("S", null, icon(IconSource.URL, "https://x/i.png"), null), null)
                .value().icon().getCachedFile()).isNull();

        setCachedFile(id, "svc_cached.svg");
        // Omitting the icon falls back to favicon -> a change from url.
        assertThat(services.update(id, service("S", null), null).value().icon()).satisfies(i -> {
            assertThat(i.getSource()).isEqualTo(IconSource.FAVICON);
            assertThat(i.getCachedFile()).isNull();
        });
    }

    @Test
    void cachedFileClearedWhenFaviconLinkChanges() {
        ServiceDto created = services.create(service("S", null, icon(IconSource.FAVICON, null), null,
                link(null, "https://a.lan"), link(null, "https://b.lan")), null).value();
        String a = created.links().get(0).getId();
        String b = created.links().get(1).getId();
        setCachedFile(created.id(), "svc_fav.png");

        IconRef sameFavicon = icon(IconSource.FAVICON, null);
        ServiceDto unchanged = services.update(created.id(), service("S", null, sameFavicon, null,
                link(a, "https://a.lan"), link(b, "https://b.lan")), null).value();
        assertThat(unchanged.icon().getCachedFile()).isEqualTo("svc_fav.png");

        IconRef fromB = icon(IconSource.FAVICON, null);
        fromB.setFaviconLinkId(b);
        ServiceDto changed = services.update(created.id(), service("S", null, fromB, null,
                link(a, "https://a.lan"), link(b, "https://b.lan")), null).value();
        assertThat(changed.icon().getFaviconLinkId()).isEqualTo(b);
        assertThat(changed.icon().getCachedFile()).isNull();
    }

    // --- forward compatibility through the API --------------------------------------------

    @Test
    void unknownServiceAndLinkFieldsSurviveApiUpdate() {
        TestStores.installFixture(dir, "config-unknown-fields.json");
        ConfigStore fixtureStore = TestStores.open(dir);
        ServiceManager manager = new ServiceManager(fixtureStore);

        manager.update("svc_prox0001", service("Proxmox VE", null, link("lnk_prox0001", "https://pve.lan")), null);

        var service = fixtureStore.read().findService("svc_prox0001").orElseThrow();
        assertThat(service.getExtra()).containsKey("serviceFuture");
        assertThat(service.getLinks().getFirst().getExtra()).containsEntry("linkFuture", 42);
    }

    @Test
    void unknownProbeFieldsSurviveApiUpdate() {
        TestStores.installFixture(dir, "config-unknown-fields.json");
        ConfigStore fixtureStore = TestStores.open(dir);
        ServiceManager manager = new ServiceManager(fixtureStore);
        ProbeConfig probe = new ProbeConfig();
        probe.setEnabled(true);

        manager.update("svc_prox0001", new ServiceRequest("Proxmox", null, null, null, null,
                List.of(new LinkRequest("lnk_prox0001", "LAN", LinkKind.IP, "https://pve.lan", true, probe)), null),
                null);

        Map<String, Object> extra = fixtureStore.read().findService("svc_prox0001").orElseThrow()
                .getLinks().getFirst().getProbe().getExtra();
        assertThat(extra).containsEntry("type", "http");

        // Also when the request omits the probe entirely.
        manager.update("svc_prox0001", service("Proxmox", null, link("lnk_prox0001", "https://pve.lan")), null);
        assertThat(fixtureStore.read().findService("svc_prox0001").orElseThrow()
                .getLinks().getFirst().getProbe().getExtra()).containsEntry("type", "http");
    }

    @Test
    void unknownIconFieldsSurviveApiUpdateAndRequestExtrasWin() {
        TestStores.installFixture(dir, "config-unknown-fields.json");
        ConfigStore fixtureStore = TestStores.open(dir);
        ServiceManager manager = new ServiceManager(fixtureStore);
        IconRef icon = icon(IconSource.FAVICON, null);
        icon.putExtra("clientField", "c");

        manager.update("svc_prox0001", service("Proxmox", null, icon, null, link("lnk_prox0001", "https://pve.lan")),
                null);

        IconRef stored = fixtureStore.read().findService("svc_prox0001").orElseThrow().getIcon();
        assertThat(stored.getExtra()).containsEntry("iconFuture", "i").containsEntry("clientField", "c");

        IconRef overriding = icon(IconSource.FAVICON, null);
        overriding.putExtra("iconFuture", "new");
        manager.update("svc_prox0001", service("Proxmox", null, overriding, null,
                link("lnk_prox0001", "https://pve.lan")), null);
        assertThat(fixtureStore.read().findService("svc_prox0001").orElseThrow().getIcon().getExtra())
                .containsEntry("iconFuture", "new").containsEntry("clientField", "c");
    }

    @Test
    void listReturnsAllWithRevision() {
        services.create(service("A", null), null);
        services.create(service("B", List.of()), null);

        var listed = services.list();

        assertThat(listed.revision()).isEqualTo(store.revision());
        assertThat(listed.value()).extracting(ServiceDto::name).containsExactly("A", "B");
        assertThat(listed.value()).extracting(ServiceDto::tabIds)
                .containsExactly(List.of(defaultTab), List.of());
        assertThat(store.read().getTabs()).extracting(Tab::getId).containsExactly(defaultTab);
    }
}
