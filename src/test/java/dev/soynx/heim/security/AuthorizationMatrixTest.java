package dev.soynx.heim.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.security.AccessPolicy.Access;

/**
 * Every toggle combination x logged in/out against representative endpoints. Any status other than 401
 * (including 404 for endpoints that live in other packages) means the request got past authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationMatrixTest {

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

    record Endpoint(HttpMethod method, String path, Access access) {
    }

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/index.html", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/favicon.ico", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/assets/app.js", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/health", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/auth/me", Access.PUBLIC),
            new Endpoint(HttpMethod.GET, "/api/dashboard", Access.VIEW),
            new Endpoint(HttpMethod.GET, "/api/status", Access.VIEW),
            new Endpoint(HttpMethod.GET, "/api/status/stream", Access.VIEW),
            new Endpoint(HttpMethod.GET, "/icons/x.png", Access.VIEW),
            new Endpoint(HttpMethod.GET, "/uploads/x.png", Access.VIEW),
            new Endpoint(HttpMethod.GET, "/custom.css", Access.VIEW),
            new Endpoint(HttpMethod.GET, "/api/settings", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/api/services", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/api/tabs", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/api/icons/library?q=proxmox", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/api/backup/export", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/api/docs", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/swagger-ui/index.html", Access.EDIT),
            new Endpoint(HttpMethod.GET, "/%61pi/services", Access.EDIT),
            new Endpoint(HttpMethod.PUT, "/api/settings", Access.EDIT),
            new Endpoint(HttpMethod.PUT, "/api/settings/auth", Access.EDIT),
            new Endpoint(HttpMethod.POST, "/api/services", Access.EDIT),
            new Endpoint(HttpMethod.POST, "/api/services/svc_missing/icon/refresh", Access.EDIT),
            new Endpoint(HttpMethod.DELETE, "/api/tabs/tab_missing", Access.EDIT),
            new Endpoint(HttpMethod.POST, "/api/backup/import", Access.EDIT));

    static Stream<Arguments> combinations() {
        List<Arguments> args = new ArrayList<>();
        for (boolean view : new boolean[] {false, true}) {
            for (boolean edit : new boolean[] {false, true}) {
                for (boolean loggedIn : new boolean[] {false, true}) {
                    args.add(Arguments.of(view, edit, loggedIn));
                }
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "view={0} edit={1} loggedIn={2}")
    @MethodSource("combinations")
    void matrix(boolean view, boolean edit, boolean loggedIn) throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        MockHttpSession session = loggedIn ? SecurityTestSupport.login(mvc) : new MockHttpSession();
        setToggles(view, edit);

        AuthSettings auth = store.read().getSettings().getAuth();
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : ENDPOINTS) {
            boolean blocked = !loggedIn && AccessPolicy.requiresLogin(endpoint.access(), auth);
            MockHttpServletRequestBuilder builder = request(endpoint.method(), URI.create(endpoint.path()))
                    .session(session);
            if (endpoint.method() != HttpMethod.GET) {
                builder.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}");
            }
            int status = mvc.perform(builder).andReturn().getResponse().getStatus();
            if (blocked != (status == 401)) {
                failures.add(endpoint.method() + " " + endpoint.path() + " -> " + status
                        + (blocked ? " (expected 401)" : " (expected not 401)"));
            }
        }
        assertThat(failures).isEmpty();
        // Restore an open configuration for the next combination.
        setToggles(false, false);
    }

    private void setToggles(boolean view, boolean edit) {
        store.update(null, config -> {
            config.getSettings().getAuth().setRequireLoginToView(view);
            config.getSettings().getAuth().setRequireLoginToEdit(edit);
            return null;
        });
    }
}
