package dev.soynx.heim.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** {@code HEIM_SECURE_COOKIES=true} marks the XSRF cookie secure along with the session cookie. */
@SpringBootTest(properties = "HEIM_SECURE_COOKIES=true")
@AutoConfigureMockMvc
class SecureCookiesTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("heim.data-dir", dataDir::toString);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void xsrfCookieIsSecure() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure("XSRF-TOKEN", true));
    }
}
