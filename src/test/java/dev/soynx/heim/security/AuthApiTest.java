package dev.soynx.heim.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("heim.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ConfigStore store;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void reset() {
        SecurityTestSupport.configure(store, encoder, false, false, false);
    }

    private ResultActions putAuth(String json, MockHttpSession session) throws Exception {
        var builder = put("/api/settings/auth").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
        if (session != null) {
            builder.session(session);
        }
        return mvc.perform(builder);
    }

    private ResultActions loginAttempt(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    // --- login / logout ---

    @Test
    void loginSucceedsWithCorrectCredentialsAndChangesSessionId() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, true);
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        MockHttpSession after = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andReturn().getRequest().getSession(false);
        assertThat(after.getId()).isNotEqualTo(before);
        mvc.perform(get("/api/settings").session(after)).andExpect(status().isOk());
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        loginAttempt("admin", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        loginAttempt("someone", "correct-horse").andExpect(status().isUnauthorized());
    }

    @Test
    void loginAlwaysFailsWithoutCredentials() throws Exception {
        loginAttempt("admin", "correct-horse").andExpect(status().isUnauthorized());
        loginAttempt("user", "password").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, true);
        MockHttpSession session = SecurityTestSupport.login(mvc);
        mvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/settings").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedResponseIsJsonWithoutBasicAuthChallenge() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, true);
        mvc.perform(get("/api/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location")).isNull());
    }

    // --- CSRF ---

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"password\":\"b\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(put("/api/settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/services").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    // --- /api/auth/me ---

    @Test
    void meWithoutCredentials() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.passwordSet").value(false))
                .andExpect(jsonPath("$.requireLoginToView").value(false))
                .andExpect(jsonPath("$.requireLoginToEdit").value(false))
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.canEdit").value(true));
    }

    @Test
    void meAnonymousWithEditLogin() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, true);
        mvc.perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.username").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.requireLoginToEdit").value(true))
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.canEdit").value(false));
    }

    @Test
    void meAnonymousWithViewLoginAndLoggedIn() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, true, false);
        mvc.perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.canView").value(false))
                .andExpect(jsonPath("$.canEdit").value(false));
        MockHttpSession session = SecurityTestSupport.login(mvc);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.canEdit").value(true));
    }

    // --- PUT /api/settings/auth ---

    @Test
    void firstTimeSetupNeedsNoCurrentPasswordAndLogsIn() throws Exception {
        putAuth("{\"username\":\"admin\",\"newPassword\":\"correct-horse\",\"requireLoginToEdit\":true}", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andExpect(jsonPath("$.requireLoginToEdit").value(true));
        AuthSettings auth = store.read().getSettings().getAuth();
        assertThat(auth.getUsername()).isEqualTo("admin");
        assertThat(encoder.matches("correct-horse", auth.getPasswordHash())).isTrue();
    }

    @Test
    void enablingLoginWithoutCredentialsIsRejected() throws Exception {
        putAuth("{\"requireLoginToEdit\":true}", null).andExpect(status().isBadRequest());
        putAuth("{\"requireLoginToView\":true}", null).andExpect(status().isBadRequest());
        putAuth("{\"username\":\"admin\",\"requireLoginToView\":true}", null).andExpect(status().isBadRequest());
        assertThat(store.read().getSettings().getAuth().isRequireLoginToView()).isFalse();
        assertThat(store.read().getSettings().getAuth().getUsername()).isNull();
    }

    @Test
    void passwordMustBeAtLeastEightCharacters() throws Exception {
        putAuth("{\"username\":\"admin\",\"newPassword\":\"short\"}", null).andExpect(status().isBadRequest());
        assertThat(store.read().getSettings().getAuth().getPasswordHash()).isNull();
    }

    @Test
    void changingPasswordRequiresCurrentPassword() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        putAuth("{\"newPassword\":\"another-password\"}", null).andExpect(status().isBadRequest());
        putAuth("{\"newPassword\":\"another-password\",\"currentPassword\":\"wrong-password\"}", null)
                .andExpect(status().isBadRequest());
        putAuth("{\"username\":\"mallory\"}", null).andExpect(status().isBadRequest());
        assertThat(store.read().getSettings().getAuth().getUsername()).isEqualTo("admin");

        putAuth("{\"newPassword\":\"another-password\",\"currentPassword\":\"correct-horse\"}", null)
                .andExpect(status().isOk());
        assertThat(encoder.matches("another-password", store.read().getSettings().getAuth().getPasswordHash()))
                .isTrue();
    }

    @Test
    void anonymousCannotChangeTogglesWithoutCurrentPassword() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        putAuth("{\"requireLoginToView\":true}", null).andExpect(status().isBadRequest());
        putAuth("{\"requireLoginToView\":true,\"currentPassword\":\"correct-horse\"}", null)
                .andExpect(status().isOk());
        assertThat(store.read().getSettings().getAuth().isRequireLoginToView()).isTrue();
    }

    @Test
    void anonymousCannotReachAuthSettingsWhenEditLoginIsOn() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, true);
        putAuth("{\"requireLoginToEdit\":false,\"currentPassword\":\"correct-horse\"}", null)
                .andExpect(status().isUnauthorized());
        assertThat(store.read().getSettings().getAuth().isRequireLoginToEdit()).isTrue();
    }

    @Test
    void adminCanDisableTogglesWithoutCurrentPassword() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, true, true);
        MockHttpSession session = SecurityTestSupport.login(mvc);
        putAuth("{\"requireLoginToView\":false,\"requireLoginToEdit\":false}", session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.requireLoginToView").value(false))
                .andExpect(jsonPath("$.requireLoginToEdit").value(false));
    }

    @Test
    void changingPasswordInvalidatesOtherSessions() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, true);
        MockHttpSession first = SecurityTestSupport.login(mvc);
        MockHttpSession second = SecurityTestSupport.login(mvc);
        MockHttpSession changed = (MockHttpSession) putAuth(
                "{\"newPassword\":\"another-password\",\"currentPassword\":\"correct-horse\"}", second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/settings").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings").session(changed)).andExpect(status().isOk());
    }

    // --- secrets never leave the server ---

    @Test
    void passwordHashIsNeverReturned() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        String hash = store.read().getSettings().getAuth().getPasswordHash();
        MockHttpSession session = SecurityTestSupport.login(mvc);
        String[] bodies = {
                mvc.perform(get("/api/settings").session(session)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                mvc.perform(get("/api/auth/me")).andReturn().getResponse().getContentAsString(),
                putAuth("{\"requireLoginToEdit\":true}", session).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
        };
        for (String body : bodies) {
            assertThat(body).doesNotContain("passwordHash").doesNotContain(hash).doesNotContain("$2a$");
        }
    }

    @Test
    void resetRunnerClearsCredentialsAndToggles() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, true, true);
        new AuthResetRunner(store, false).run(null);
        assertThat(store.read().getSettings().getAuth().getUsername()).isEqualTo("admin");
        new AuthResetRunner(store, true).run(null);
        AuthSettings auth = store.read().getSettings().getAuth();
        assertThat(auth.getUsername()).isNull();
        assertThat(auth.getPasswordHash()).isNull();
        assertThat(auth.isRequireLoginToView()).isFalse();
        assertThat(auth.isRequireLoginToEdit()).isFalse();
    }
}
