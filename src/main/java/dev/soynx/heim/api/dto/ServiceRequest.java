package dev.soynx.heim.api.dto;

import java.util.List;

import dev.soynx.heim.config.model.IconRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of service create/update (full replacement). {@code tabIds}: null keeps the current
 * membership on update and means "default tab" on create; an empty list removes it from all tabs.
 * {@code icon.cachedFile} is managed by the server and ignored here.
 */
public record ServiceRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description,
        @Size(max = 50) String color,
        IconRef icon,
        String primaryLinkId,
        List<@Valid LinkRequest> links,
        List<String> tabIds) {
}
