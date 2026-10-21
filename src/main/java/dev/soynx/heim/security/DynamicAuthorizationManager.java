package dev.soynx.heim.security;

import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import dev.soynx.heim.config.ConfigStore;
import dev.soynx.heim.config.model.AuthSettings;
import jakarta.servlet.http.HttpServletRequest;

/** Applies {@link AccessPolicy} with the login toggles as they are in the config right now. */
@Component
public class DynamicAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);
    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

    private final ConfigStore store;

    public DynamicAuthorizationManager(ConfigStore store) {
        this.store = store;
    }

    @Override
    @SuppressWarnings("deprecation")
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        AccessPolicy.Access access = AccessPolicy.classify(request.getMethod(), AccessPolicy.pathOf(request));
        if (access == AccessPolicy.Access.PUBLIC) {
            return GRANTED;
        }
        AuthSettings auth = store.auth();
        if (!AccessPolicy.requiresLogin(access, auth)) {
            return GRANTED;
        }
        return AdminSession.isAdmin(authentication.get(), auth) ? GRANTED : DENIED;
    }
}
