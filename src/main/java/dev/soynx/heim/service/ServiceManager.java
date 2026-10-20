package dev.soynx.heim.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import dev.soynx.heim.config.Versioned;
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

    public Versioned<List<ServiceDto>> list() {
        HeimConfig config = store.read();
        List<ServiceDto> services = config.getServices().stream().map(s -> toDto(config, s)).toList();
        return new Versioned<>(services, config.getRevision());
    }

    public Versioned<ServiceDto> get(String id) {
        HeimConfig config = store.read();
        Service service = config.findService(id).orElseThrow(() -> NotFoundException.service(id));
        return new Versioned<>(toDto(config, service), config.getRevision());
    }

    public Versioned<ServiceDto> create(ServiceRequest request, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
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
        });
    }

    public Versioned<ServiceDto> update(String id, ServiceRequest request, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            Service service = config.findService(id).orElseThrow(() -> NotFoundException.service(id));
            apply(service, request);
            if (request.tabIds() != null) {
                Membership.setTabs(config, id, validTabIds(config, request.tabIds()));
            }
            return toDto(config, service);
        });
    }

    public Versioned<Void> delete(String id, Long expectedRevision) {
        return store.update(expectedRevision, config -> {
            if (!config.getServices().removeIf(s -> s.getId().equals(id))) {
                throw NotFoundException.service(id);
            }
            Membership.removeEverywhere(config, id);
            return null;
        });
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
        link.setProbe(probe);
    }

    /**
     * Builds the stored icon. The cached file survives only if the icon definition is unchanged;
     * otherwise it is cleared so the icon gets fetched again.
     */
    private static IconRef icon(IconRef requested, IconRef previous, Map<String, String> linkIds) {
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
        return icon;
    }
}
