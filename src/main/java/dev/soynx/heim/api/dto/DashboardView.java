package dev.soynx.heim.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.soynx.heim.config.model.BackgroundRef;
import dev.soynx.heim.config.model.BackgroundSource;
import dev.soynx.heim.config.model.HeimConfig;
import dev.soynx.heim.config.model.IconRef;
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
            @JsonProperty("isDefault") boolean isDefault, boolean showAll, List<ServiceDto> services) {
    }

    public static DashboardView from(HeimConfig config) {
        Settings s = config.getSettings();
        PublicSettings settings = new PublicSettings(s.getTitle(), s.getTheme(), background(s.getBackground()),
                !s.getCustomCss().isBlank(), s.getAuth().isRequireLoginToView(), s.getAuth().isRequireLoginToEdit());
        List<DashboardTab> tabs = config.getTabs().stream().map(tab -> tab(config, tab)).toList();
        return new DashboardView(config.getRevision(), settings, tabs);
    }

    private static DashboardTab tab(HeimConfig config, Tab tab) {
        List<ServiceDto> services = Membership.resolveServices(config, tab).stream()
                .map(s -> ServiceDto.from(s, Membership.tabIdsOf(config, s.getId())))
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
