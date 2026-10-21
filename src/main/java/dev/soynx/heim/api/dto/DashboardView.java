package dev.soynx.heim.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.soynx.heim.config.model.BackgroundRef;
import dev.soynx.heim.config.model.BackgroundSource;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.LinkKind;
import dev.soynx.heim.config.model.Service;
import dev.soynx.heim.config.model.Settings;
import dev.soynx.heim.config.model.Tab;
import dev.soynx.heim.service.Membership;

/** Everything the dashboard needs to render, without secrets. */
public record DashboardView(long revision, PublicSettings settings, List<DashboardTab> tabs) {

    /** {@code background.url} is ready to use in CSS; {@code customCss} tells whether /custom.css has content. */
    public record PublicSettings(String title, String theme, Background background, boolean customCss,
            boolean requireLoginToView, boolean requireLoginToEdit) {
    }

    public record Background(BackgroundSource source, String url) {
    }

    public record DashboardTab(String id, String name, IconRef icon, String color,
            @JsonProperty("isDefault") boolean isDefault, boolean showAll, List<DashboardService> services) {
    }

    /** A service as anonymous viewers may see it: no timestamps and no probe configuration. */
    public record DashboardService(String id, String name, String description, String color, IconRef icon,
            String primaryLinkId, List<DashboardLink> links, List<String> tabIds) {

        static DashboardService from(Service service, List<String> tabIds) {
            return new DashboardService(service.getId(), service.getName(), service.getDescription(),
                    service.getColor(), service.getIcon(), service.getPrimaryLinkId(),
                    service.getLinks().stream().map(DashboardLink::from).toList(), tabIds);
        }
    }

    /** Probe URLs may carry credentials and are for editors only; viewers just learn whether a status exists. */
    public record DashboardLink(String id, String label, LinkKind kind, String url, boolean openInNewTab,
            boolean probeEnabled) {

        static DashboardLink from(Link link) {
            return new DashboardLink(link.getId(), link.getLabel(), link.getKind(), link.getUrl(),
                    link.isOpenInNewTab(), link.getProbe() != null && link.getProbe().isEnabled());
        }
    }

    public static DashboardView from(HeimConfig config) {
        Settings s = config.getSettings();
        PublicSettings settings = new PublicSettings(s.getTitle(), s.getTheme(), background(s.getBackground()),
                !s.getCustomCss().isBlank(), s.getAuth().isRequireLoginToView(), s.getAuth().isRequireLoginToEdit());
        List<DashboardTab> tabs = config.getTabs().stream().map(tab -> tab(config, tab)).toList();
        return new DashboardView(config.getRevision(), settings, tabs);
    }

    private static DashboardTab tab(HeimConfig config, Tab tab) {
        List<DashboardService> services = Membership.resolveServices(config, tab).stream()
                .map(s -> DashboardService.from(s, Membership.tabIdsOf(config, s.getId())))
                .toList();
        return new DashboardTab(tab.getId(), tab.getName(), tab.getIcon(), tab.getColor(), tab.isDefault(),
                tab.isShowAll(), services);
    }

    private static Background background(BackgroundRef ref) {
        String url = switch (ref.getSource()) {
            case NONE -> null;
            case UPLOAD -> "/uploads/" + ref.getCachedFile();
            case URL -> ref.getCachedFile() != null ? "/uploads/" + ref.getCachedFile() : ref.getValue();
        };
        return new Background(ref.getSource(), url);
    }
}
