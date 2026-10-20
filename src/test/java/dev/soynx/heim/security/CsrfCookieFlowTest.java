package dev.soynx.heim.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import dev.soynx.heim.config.ConfigStore;
import jakarta.servlet.http.Cookie;

/**
 * The real SPA flow with the production CSRF repository. Kept in its own context and without the
 * {@code csrf()} test post-processor, which swaps the filter's token repository for the rest of a context.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CsrfCookieFlowTest {

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

    private static final String LOGIN = "{\"username\":\"admin\",\"password\":\"correct-horse\"}";

    @Test
    void xsrfCookieFromFirstGetWorksAsHeader() throws Exception {
        SecurityTestSupport.configure(store, encoder, true, false, false);
        Cookie xsrf = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").cookie(xsrf).contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                // A fresh token is issued after login.
                .andExpect(cookie().value("XSRF-TOKEN", Matchers.not(xsrf.getValue())));
    }
}
