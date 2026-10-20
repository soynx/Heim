package dev.soynx.heim.api.dto;

import dev.soynx.heim.config.model.BackgroundSource;
import dev.soynx.heim.config.model.ProbeDefaults;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/settings}. Null fields are left unchanged. Auth is not settable here.
 * Background: {@code none} clears it, {@code url} needs {@code value}; {@code upload} is only
 * accepted to keep an existing upload (new uploads go through {@code POST /api/settings/background}).
 */
public record SettingsRequest(
        @Size(max = 200) String title,
        @Size(max = 50) String theme,
        BackgroundRequest background,
        @Size(max = 500_000) String customCss,
        @Valid ProbeDefaults probe) {

    public record BackgroundRequest(BackgroundSource source, @Size(max = 2048) String value) {
    }
}
