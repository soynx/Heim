package dev.soynx.heim.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.soynx.heim.config.model.IconRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of tab create/update. Groups are managed via {@code PUT /api/tabs/{id}/groups}.
 * {@code isDefault=true} makes this the default tab; false/null leaves the default unchanged.
 * {@code showAll=null} keeps the current value (false on create).
 */
public record TabRequest(
        @NotBlank @Size(max = 100) String name,
        IconRef icon,
        @Size(max = 50) String color,
        @JsonProperty("isDefault") Boolean isDefault,
        Boolean showAll) {
}
