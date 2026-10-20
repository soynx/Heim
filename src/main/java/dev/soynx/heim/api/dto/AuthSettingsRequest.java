package dev.soynx.heim.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/settings/auth}. Null fields are left unchanged. {@code currentPassword} is required
 * once a password is set (except for toggle-only changes by the logged-in admin).
 */
public record AuthSettingsRequest(
        @Size(max = 64) String username,
        @Size(min = 8, max = 72) String newPassword,
        @Size(max = 200) String currentPassword,
        Boolean requireLoginToView,
        Boolean requireLoginToEdit) {
}
