package dev.soynx.heim.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.soynx.heim.api.dto.LinkRequest;
import dev.soynx.heim.api.dto.ServiceDto;
import dev.soynx.heim.api.dto.ServiceRequest;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.IdGenerator;
import dev.soynx.heim.config.ResourceTags;
import dev.soynx.heim.config.Tagged;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.config.model.Extensible;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.IconSource;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.ProbeConfig;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.error.BadRequestException;
import dev.soynx.heim.error.NotFoundException;

/** Service CRUD including tab membership via {@code tabIds}. */
@Component
public class ServiceManager {

    private final ConfigStore store;

    public ServiceManager(ConfigStore store) {
        this.store = store;
    }

    public Tagged<List<ServiceDto>> list() {
        HeimConfig config = store.read();
        List<ServiceDto> services = config.getServices().stream().map(s -> toDto(config, s)).toList();
        return new Tagged<>(services, listTag(config));
    }

    public Tagged<ServiceDto> get(String id) {
        HeimConfig config = store.read();
        Service service = config.findService(id).orElseThrow(() -> NotFoundException.service(id));
        return tagged(toDto(config, service));
    }

    /** {@code expectedTag} is checked against the service list tag (see {@link #listTag}). */
    public Tagged<ServiceDto> create(ServiceRequest request, String expectedTag) {
        return tagged(store.update(null, config -> {
            ResourceTags.check(expectedTag, listTag(config), "Service list");
            Service service = new Service();
            service.setId(IdGenerator.serviceId());
            service.setCreatedAt(Instant.now());
            apply(service, request);
            config.getServices().add(service);
            List<String> tabIds = request.tabIds() != null
                    ? request.tabIds()
                    : config.getTabs().stream().filter(Tab::isDefault).map(Tab::getId).toList();
            Membership.setTabs(config, service.getId(), validTabIds(config, tabIds));
            return toDto(config, service);
        }).value());
    }

    public Tagged<ServiceDto> update(String id, ServiceRequest request, String expectedTag) {
        return tagged(store.update(null, config -> {
            Service service = config.findService(id).orElseThrow(() -> NotFoundException.service(id));
            checkTag(expectedTag, config, service);
            apply(service, request);
            if (request.tabIds() != null) {
                Membership.setTabs(config, id, validTabIds(config, request.tabIds()));
            }
            return toDto(config, service);
        }).value());
    }

    public Versioned<Void> delete(String id, String expectedTag) {
        return store.update(null, config -> {
            Service service = config.findService(id).orElseThrow(() -> NotFoundException.service(id));
            checkTag(expectedTag, config, service);
            config.getServices().remove(service);
            Membership.removeEverywhere(config, id);
            return null;
        });
    }

    /**
     * Tag of a service: its API representation without the server-managed {@code icon.cachedFile}
     * (written by background icon fetches) and {@code updatedAt}.
     */
    public static String tagOf(ServiceDto service) {
        return ResourceTags.of(service, "icon.cachedFile", "updatedAt");
    }

    /** Tag of the service list: the ordered service ids, so it changes on create and delete only. */
    static String listTag(HeimConfig config) {
        return ResourceTags.of(config.getServices().stream().map(Service::getId).toList());
    }

    /** For use inside a {@link ConfigStore#update} mutator. */
    public static void checkTag(String expectedTag, HeimConfig config, Service service) {
        ResourceTags.check(expectedTag, tagOf(toDto(config, service)), "Service " + service.getId());
    }

    private static Tagged<ServiceDto> tagged(ServiceDto service) {
        return new Tagged<>(service, tagOf(service));
    }

    private static ServiceDto toDto(HeimConfig config, Service service) {
        return ServiceDto.from(service, Membership.tabIdsOf(config, service.getId()));
    }

    private static Set<String> validTabIds(HeimConfig config, List<String> tabIds) {
        Set<String> result = new HashSet<>();
        for (String tabId : tabIds) {
            if (config.findTab(tabId).isEmpty()) {
                throw new BadRequestException("Unknown tab: " + tabId);
            }
            result.add(tabId);
        }
        return result;
    }

    /** Overwrites the service's editable fields from the request. */
    private static void apply(Service service, ServiceRequest request) {
        // Read before the links are replaced: kept links are updated in place.
        String previousFaviconUrl = service.faviconLink().map(Link::getUrl).orElse(null);
        service.setName(request.name().trim());
        service.setDescription(request.description());
        service.setColor(request.color());

        // Maps ids as sent by the client to final link ids, so references in the same request resolve.
        Map<String, String> linkIds = new HashMap<>();
        List<Link> links = new ArrayList<>();
        List<LinkRequest> requested = request.links() != null ? request.links() : List.of();
        for (int i = 0; i < requested.size(); i++) {
            LinkRequest lr = requested.get(i);
            boolean keep = lr.id() != null && !linkIds.containsKey(lr.id()) && service.findLink(lr.id()).isPresent();
            Link link = keep ? service.findLink(lr.id()).get() : new Link();
            link.setId(keep ? lr.id() : IdGenerator.linkId());
            if (lr.id() != null) {
                linkIds.putIfAbsent(lr.id(), link.getId());
            }
            applyLink(link, lr, "links[" + i + "]");
            links.add(link);
        }

        IconRef previousIcon = service.getIcon();
        service.setLinks(links);
        String primary = linkIds.get(request.primaryLinkId());
        service.setPrimaryLinkId(primary != null || links.isEmpty() ? primary : links.getFirst().getId());
        service.setIcon(icon(request.icon(), previousIcon, linkIds));
        if (service.getIcon().getSource() == IconSource.FAVICON
                && !Objects.equals(previousFaviconUrl, service.faviconLink().map(Link::getUrl).orElse(null))) {
            service.getIcon().setCachedFile(null); // the favicon comes from another page now: fetch again
        }
        service.setUpdatedAt(Instant.now());
    }

    private static void applyLink(Link link, LinkRequest request, String field) {
        link.setLabel(request.label());
        link.setKind(request.kind());
        link.setUrl(Urls.requireAbsolute(request.url(), field + ".url"));
        link.setOpenInNewTab(request.openInNewTab() == null || request.openInNewTab());
        ProbeConfig probe = request.probe() != null ? request.probe() : new ProbeConfig();
        if (probe.getUrl() != null && probe.getUrl().isBlank()) {
            probe.setUrl(null);
        }
        if (probe.getUrl() != null) {
            probe.setUrl(Urls.requireHttp(probe.getUrl(), field + ".probe.url"));
        }
        keepExtra(link.getProbe(), probe);
        link.setProbe(probe);
    }

    /**
     * Builds the stored icon. The cached file survives only if the icon definition is unchanged;
     * otherwise it is cleared so the icon gets fetched again. A client-sent cached file is ignored.
     * Also used for tab icons, which have no links ({@code linkIds} empty).
     */
    static IconRef icon(IconRef requested, IconRef previous, Map<String, String> linkIds) {
        IconRef icon = requested != null ? requested : new IconRef();
        if (icon.getSource() == null) {
            icon.setSource(IconSource.FAVICON);
        }
        switch (icon.getSource()) {
            case URL -> icon.setValue(Urls.requireHttp(icon.getValue(), "icon.value"));
            case LIBRARY -> {
                if (icon.getValue() == null || icon.getValue().isBlank()) {
                    throw new BadRequestException("icon.value must name a library icon");
                }
            }
            case FAVICON, UPLOAD -> {
            }
        }
        icon.setFaviconLinkId(icon.getFaviconLinkId() != null ? linkIds.get(icon.getFaviconLinkId()) : null);
        boolean unchanged = previous != null
                && previous.getSource() == icon.getSource()
                && Objects.equals(previous.getValue(), icon.getValue())
                && Objects.equals(previous.getFaviconLinkId(), icon.getFaviconLinkId());
        icon.setCachedFile(unchanged ? previous.getCachedFile() : null);
        keepExtra(previous, icon);
        return icon;
    }

    /**
     * Carries unknown fields of the stored object over to its replacement built from a request, so
     * fields written by a newer version are not lost on edit. Fields sent in the request win.
     */
    static void keepExtra(Extensible previous, Extensible replacement) {
        if (previous == null || previous == replacement || previous.getExtra().isEmpty()) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(previous.getExtra());
        merged.putAll(replacement.getExtra());
        replacement.getExtra().clear();
        replacement.getExtra().putAll(merged);
    }
}
