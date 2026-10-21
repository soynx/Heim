package dev.soynx.heim.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;

/** The single admin account, read live from the config. Without credentials nobody can log in. */
@Component
public class ConfigUserDetailsService implements UserDetailsService {

    private final ConfigStore store;

    public ConfigUserDetailsService(ConfigStore store) {
        this.store = store;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AuthSettings auth = store.auth();
        if (!AdminSession.hasCredentials(auth) || !auth.getUsername().equals(username)) {
            throw new UsernameNotFoundException("Unknown user");
        }
        return User.withUsername(auth.getUsername())
                .password(auth.getPasswordHash())
                .authorities(AdminSession.ROLE)
                .build();
    }
}
