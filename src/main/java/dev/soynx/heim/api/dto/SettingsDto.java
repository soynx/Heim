package dev.soynx.heim.api.dto;

import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.config.model.BackgroundRef;
import dev.soynx.heim.config.model.ProbeDefaults;
import dev.soynx.heim.config.model.Settings;

/** Full settings for the editor. The password hash is never included. */
public record SettingsDto(
        String title,
        String theme,
        BackgroundRef background,
        String customCss,
        ProbeDefaults probe,
        AuthStatus auth) {

    public record AuthStatus(boolean requireLoginToView, boolean requireLoginToEdit, String username,
            boolean passwordSet) {

        public static AuthStatus from(AuthSettings auth) {
            return new AuthStatus(auth.isRequireLoginToView(), auth.isRequireLoginToEdit(), auth.getUsername(),
                    auth.getPasswordHash() != null);
        }
    }

    /** The admin username is only for the admin, like in {@link AuthStatusResponse}. */
    public SettingsDto forViewer(boolean admin) {
        if (admin || auth.username() == null) {
            return this;
        }
        return new SettingsDto(title, theme, background, customCss, probe,
                new AuthStatus(auth.requireLoginToView(), auth.requireLoginToEdit(), null, auth.passwordSet()));
    }

    public static SettingsDto from(Settings settings) {
        return new SettingsDto(settings.getTitle(), settings.getTheme(), settings.getBackground(),
                settings.getCustomCss(), settings.getProbe(), AuthStatus.from(settings.getAuth()));
    }
}
