package dev.soynx.heim.security;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import dev.soynx.heim.api.dto.AuthSettingsRequest;
import dev.soynx.heim.api.dto.AuthStatusResponse;
import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.error.BadRequestException;

/** Credentials and login toggles ({@code PUT /api/settings/auth}). */
@Component
public class AuthSettingsService {

    /** BCrypt only looks at the first 72 bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final ConfigStore store;
    private final PasswordEncoder encoder;

    public AuthSettingsService(ConfigStore store, PasswordEncoder encoder) {
        this.store = store;
        this.encoder = encoder;
    }

    public AuthSettings current() {
        return store.read().getSettings().getAuth();
    }

    public AuthStatusResponse status(Authentication authentication) {
        AuthSettings auth = current();
        return AuthStatusResponse.of(auth, AdminSession.isAdmin(authentication, auth));
    }

    /**
     * Applies the change. Changing credentials needs {@code currentPassword} whenever a password is set;
     * toggles can be changed without it only by the logged-in admin. Login cannot be enabled before both a
     * username and a password exist.
     *
     * @param caller the current authentication, used to recognize the logged-in admin
     * @return the saved settings and whether the credentials changed (the caller should then be logged in
     *         with the new ones)
     */
    public Versioned<Result> update(AuthSettingsRequest request, Authentication caller) {
        String username = request.username() != null ? validUsername(request.username()) : null;
        String newHash = request.newPassword() != null ? encoder.encode(validPassword(request.newPassword())) : null;

        return store.update(null, config -> {
            AuthSettings auth = config.getSettings().getAuth();
            boolean admin = AdminSession.isAdmin(caller, auth);
            boolean credentialsChange = newHash != null
                    || (username != null && !username.equals(auth.getUsername()));
            boolean togglesChange = changes(request.requireLoginToView(), auth.isRequireLoginToView())
                    || changes(request.requireLoginToEdit(), auth.isRequireLoginToEdit());

            if (auth.getPasswordHash() != null && (credentialsChange || (togglesChange && !admin))) {
                if (request.currentPassword() == null) {
                    throw new BadRequestException("currentPassword is required");
                }
                if (!encoder.matches(request.currentPassword(), auth.getPasswordHash())) {
                    throw new BadRequestException("currentPassword is incorrect");
                }
            }

            if (username != null) {
                auth.setUsername(username);
            }
            if (newHash != null) {
                if (auth.getUsername() == null) {
                    throw new BadRequestException("username is required to set a password");
                }
                auth.setPasswordHash(newHash);
            }
            if (request.requireLoginToView() != null) {
                auth.setRequireLoginToView(request.requireLoginToView());
            }
            if (request.requireLoginToEdit() != null) {
                auth.setRequireLoginToEdit(request.requireLoginToEdit());
            }
            if ((auth.isRequireLoginToView() || auth.isRequireLoginToEdit()) && !AdminSession.hasCredentials(auth)) {
                throw new BadRequestException("Set a username and password before enabling login");
            }
            return new Result(auth, credentialsChange);
        });
    }

    /** Saved auth settings plus whether the credentials changed. */
    public record Result(AuthSettings auth, boolean credentialsChanged) {
    }

    private static boolean changes(Boolean requested, boolean current) {
        return requested != null && !Objects.equals(requested, current);
    }

    private static String validUsername(String raw) {
        String username = raw.trim();
        if (username.isEmpty()) {
            throw new BadRequestException("username must not be blank");
        }
        if (username.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("username must not contain control characters");
        }
        return username;
    }

    private static String validPassword(String password) {
        if (password.length() < 8) {
            throw new BadRequestException("newPassword must be at least 8 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("newPassword must be at most 72 bytes");
        }
        return password;
    }
}
