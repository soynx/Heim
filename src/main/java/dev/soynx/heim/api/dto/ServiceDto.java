package dev.soynx.heim.api.dto;

import java.time.Instant;
import java.util.List;

import dev.soynx.heim.config.model.IconRef;
import dev.soynx.heim.config.model.Link;
import dev.soynx.heim.config.model.Service;

/** A service as returned by the API, with its computed tab membership. */
public record ServiceDto(
        String id,
        String name,
        String description,
        String color,
        IconRef icon,
        String primaryLinkId,
        List<Link> links,
        List<String> tabIds,
        Instant createdAt,
        Instant updatedAt) {

    public static ServiceDto from(Service service, List<String> tabIds) {
        return new ServiceDto(service.getId(), service.getName(), service.getDescription(), service.getColor(),
                service.getIcon(), service.getPrimaryLinkId(), service.getLinks(), tabIds,
                service.getCreatedAt(), service.getUpdatedAt());
    }
}
