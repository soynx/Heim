package dev.soynx.heim.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;

/**
 * Lockout recovery: with {@code HEIM_RESET_AUTH=true} (property {@code heim.reset-auth}) the credentials and
 * both login toggles are cleared on startup.
 */
@Component
public class AuthResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthResetRunner.class);

    private final ConfigStore store;
    private final boolean reset;

    public AuthResetRunner(ConfigStore store, @Value("${heim.reset-auth:false}") boolean reset) {
        this.store = store;
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!reset) {
            return;
        }
        store.update(null, config -> {
            AuthSettings auth = config.getSettings().getAuth();
            auth.setUsername(null);
            auth.setPasswordHash(null);
            auth.setRequireLoginToView(false);
            auth.setRequireLoginToEdit(false);
            return null;
        });
        log.warn("HEIM_RESET_AUTH is set: credentials and login requirements were cleared. "
                + "Anyone can now view and edit this dashboard; unset HEIM_RESET_AUTH and set new credentials.");
    }
}
