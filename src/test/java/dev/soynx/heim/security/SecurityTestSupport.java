package dev.soynx.heim.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;

/** Shared helpers for the security tests. */
final class SecurityTestSupport {

    static final String USER = "admin";
    static final String PASSWORD = "correct-horse";

    private SecurityTestSupport() {
    }

    /** Resets auth to: credentials set (or not) and the given toggles. */
    static void configure(ConfigStore store, PasswordEncoder encoder, boolean credentials, boolean view,
            boolean edit) {
        String hash = credentials ? encoder.encode(PASSWORD) : null;
        store.update(null, config -> {
            AuthSettings auth = new AuthSettings();
            if (credentials) {
                auth.setUsername(USER);
                auth.setPasswordHash(hash);
            }
            auth.setRequireLoginToView(view);
            auth.setRequireLoginToEdit(edit);
            config.getSettings().setAuth(auth);
            return null;
        });
    }

    static MockHttpSession login(MockMvc mvc) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }
}
