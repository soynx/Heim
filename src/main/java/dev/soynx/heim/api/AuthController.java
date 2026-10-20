package dev.soynx.heim.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.soynx.heim.api.dto.AuthLoginRequest;
import dev.soynx.heim.api.dto.AuthSettingsRequest;
import dev.soynx.heim.api.dto.AuthStatusResponse;
import dev.soynx.heim.config.Versioned;
import dev.soynx.heim.config.model.AuthSettings;
import dev.soynx.heim.security.AdminSession;
import dev.soynx.heim.security.AuthSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/** Session login/logout and the credential/toggle settings. */
@RestController
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthSettingsService authSettings;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final CsrfLogoutHandler csrfLogout;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authenticationManager, AuthSettingsService authSettings,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessionStrategy,
            CsrfTokenRepository csrfTokens) {
        this.authenticationManager = authenticationManager;
        this.authSettings = authSettings;
        this.contexts = contexts;
        this.sessionStrategy = sessionStrategy;
        this.csrfLogout = new CsrfLogoutHandler(csrfTokens);
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody AuthLoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (request.username() == null || request.password() == null) {
            return invalidCredentials();
        }
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException e) {
            return invalidCredentials();
        }
        AuthSettings auth = authSettings.current();
        establishSession(AdminSession.create(auth), httpRequest, httpResponse);
        return ResponseEntity.ok(AuthStatusResponse.of(auth, true));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = holder.getContext().getAuthentication();
        csrfLogout.logout(request, response, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/auth/me")
    public AuthStatusResponse me() {
        return authSettings.status(holder.getContext().getAuthentication());
    }

    @PutMapping("/api/settings/auth")
    public ResponseEntity<AuthStatusResponse> updateSettings(@Valid @RequestBody AuthSettingsRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication caller = holder.getContext().getAuthentication();
        Versioned<AuthSettingsService.Result> result = authSettings.update(request, caller);
        AuthSettings auth = result.value().auth();
        boolean admin = AdminSession.isAdmin(caller, auth);
        if (result.value().credentialsChanged() && AdminSession.hasCredentials(auth)) {
            // The caller just proved (or set) the credentials: keep them logged in under the new ones.
            establishSession(AdminSession.create(auth), httpRequest, httpResponse);
            admin = true;
        }
        return ResponseEntity.ok().eTag(ETags.of(result.revision())).body(AuthStatusResponse.of(auth, admin));
    }

    private void establishSession(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response) {
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    private static ResponseEntity<ApiError> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("unauthorized", "Invalid username or password"));
    }
}
