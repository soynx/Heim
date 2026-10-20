package dev.soynx.heim.api.dto;

import dev.soynx.heim.config.model.LinkKind;
import dev.soynx.heim.config.model.ProbeConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A link inside a {@link ServiceRequest}. {@code id} keeps an existing link (and its probe history);
 * unknown or absent ids create a new link, and the sent id can still be used as a reference in
 * {@code primaryLinkId} / {@code icon.faviconLinkId} of the same request.
 */
public record LinkRequest(
        String id,
        @Size(max = 100) String label,
        LinkKind kind,
        @NotBlank @Size(max = 2048) String url,
        Boolean openInNewTab,
        @Valid ProbeConfig probe) {
}
