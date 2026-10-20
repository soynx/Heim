package dev.soynx.heim.security;

import org.springframework.web.util.UrlPathHelper;

import dev.soynx.heim.config.model.AuthSettings;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Which login toggle guards a request. Classification uses the decoded path within the application
 * (the path Spring MVC matches against), so percent-encoded paths cannot slip past it.
 */
public final class AccessPolicy {

    /** Access class of a request. */
    public enum Access {
        /** Always reachable: SPA files, login API, health, error page. */
        PUBLIC,
        /** Dashboard data; guarded by {@code requireLoginToView}. */
        VIEW,
        /** Everything that reads or changes configuration; guarded by either toggle. */
        EDIT
    }

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private AccessPolicy() {
    }

    public static String pathOf(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return path.isEmpty() ? "/" : path;
    }

    public static Access classify(String method, String path) {
        if (path.equals("/error") || path.equals("/api/health") || under(path, "/api/auth")) {
            return Access.PUBLIC;
        }
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (!read) {
            return Access.EDIT;
        }
        if (path.equals("/api/dashboard") || under(path, "/api/status") || under(path, "/icons")
                || under(path, "/uploads") || path.equals("/custom.css")) {
            return Access.VIEW;
        }
        if (under(path, "/api") || under(path, "/swagger-ui") || under(path, "/v3/api-docs")) {
            return Access.EDIT;
        }
        return Access.PUBLIC;
    }

    /**
     * Whether a request of the given class needs a logged-in admin. Edit access also requires login when
     * only {@code requireLoginToView} is on: a visitor who may not see the dashboard must not be able to
     * read (or change) the configuration behind it either.
     */
    public static boolean requiresLogin(Access access, AuthSettings auth) {
        return switch (access) {
            case PUBLIC -> false;
            case VIEW -> auth.isRequireLoginToView();
            case EDIT -> auth.isRequireLoginToEdit() || auth.isRequireLoginToView();
        };
    }

    public static boolean canView(AuthSettings auth, boolean admin) {
        return admin || !requiresLogin(Access.VIEW, auth);
    }

    public static boolean canEdit(AuthSettings auth, boolean admin) {
        return admin || !requiresLogin(Access.EDIT, auth);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
