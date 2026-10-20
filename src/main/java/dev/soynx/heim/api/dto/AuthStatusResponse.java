package dev.soynx.heim.api.dto;

import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.security.AccessPolicy;

/**
 * Login state of the caller plus the active toggles. {@code username} is only revealed to the logged-in
 * admin; the password hash is never included.
 */
public record AuthStatusResponse(
        boolean authenticated,
        String username,
        boolean passwordSet,
        boolean requireLoginToView,
        boolean requireLoginToEdit,
        boolean canView,
        boolean canEdit) {

    public static AuthStatusResponse of(AuthSettings auth, boolean admin) {
        return new AuthStatusResponse(admin, admin ? auth.getUsername() : null, auth.getPasswordHash() != null,
                auth.isRequireLoginToView(), auth.isRequireLoginToEdit(),
                AccessPolicy.canView(auth, admin), AccessPolicy.canEdit(auth, admin));
    }
}
