package dev.soynx.heim.service;

import java.util.List;

import dev.soynx.heim.api.dto.LinkRequest;
import dev.soynx.heim.api.dto.ServiceRequest;
import dev.soynx.heim.api.dto.TabRequest;
import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.IconSource;

/** Compact request builders for manager tests. */
final class Requests {

    private Requests() {
    }

    static LinkRequest link(String id, String url) {
        return new LinkRequest(id, null, null, url, null, null);
    }

    static ServiceRequest service(String name, List<String> tabIds, LinkRequest... links) {
        return new ServiceRequest(name, null, null, null, null, List.of(links), tabIds);
    }

    static ServiceRequest service(String name, String primaryLinkId, IconRef icon, List<String> tabIds,
            LinkRequest... links) {
        return new ServiceRequest(name, null, null, icon, primaryLinkId, List.of(links), tabIds);
    }

    static TabRequest tab(String name) {
        return new TabRequest(name, null, null, null, null);
    }

    static TabRequest tab(String name, Boolean isDefault, Boolean showAll) {
        return new TabRequest(name, null, null, isDefault, showAll);
    }

    static IconRef icon(IconSource source, String value) {
        IconRef icon = new IconRef();
        icon.setSource(source);
        icon.setValue(value);
        return icon;
    }
}
