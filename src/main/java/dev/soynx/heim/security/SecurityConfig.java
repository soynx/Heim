package dev.soynx.heim.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

/**
 * Session-cookie login for the single admin, CSRF via the {@code XSRF-TOKEN} cookie, and per-request
 * authorization from the live login toggles ({@link DynamicAuthorizationManager}). Login and logout are
 * handled by {@code AuthController}; there is no form login, HTTP basic or generated default user.
 */
@Configuration
public class SecurityConfig {

    private static final SpaCsrfTokenRequestHandler CSRF_HANDLER = new SpaCsrfTokenRequestHandler();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DynamicAuthorizationManager authorization,
            CsrfTokenRepository csrfTokens, SecurityContextRepository contexts, ObjectMapper mapper)
            throws Exception {
        JsonSecurityHandlers handlers = new JsonSecurityHandlers(mapper);
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(CSRF_HANDLER))
                .securityContext(context -> context.securityContextRepository(contexts))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        // Error pages and async (SSE) dispatches belong to a request that was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .anyRequest().access(authorization))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /** {@code secure} follows the session cookie setting ({@code HEIM_SECURE_COOKIES}). */
    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secure));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Applied on login: new session id (fixation protection) and a fresh CSRF token. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokens) {
        CsrfAuthenticationStrategy csrf = new CsrfAuthenticationStrategy(csrfTokens);
        csrf.setRequestHandler(CSRF_HANDLER);
        return new CompositeSessionAuthenticationStrategy(
                List.of(new ChangeSessionIdAuthenticationStrategy(), csrf));
    }

    /** The session cookie is not sent on cross-site subrequests. */
    @Bean
    CookieSameSiteSupplier sessionCookieSameSite() {
        return CookieSameSiteSupplier.ofLax().whenHasName("JSESSIONID");
    }
}
