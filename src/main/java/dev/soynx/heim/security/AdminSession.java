package dev.soynx.heim.security;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import dev.soynx.heim.config.model.AuthSettings;

/**
 * The authentication stored in a logged-in session. It carries a stamp derived from the credentials it was
 * issued for, so changing the username or password (or resetting auth) invalidates every other session.
 */
public final class AdminSession {

    public static final String ROLE = "ROLE_ADMIN";

    private AdminSession() {
    }

    /** Fingerprint of the credentials a session was issued for. Never contains the hash itself. */
    public record CredentialStamp(String value) implements Serializable {

        static CredentialStamp of(AuthSettings auth) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest((auth.getUsername() + "\0" + auth.getPasswordHash())
                        .getBytes(StandardCharsets.UTF_8));
                return new CredentialStamp(HexFormat.of().formatHex(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static boolean hasCredentials(AuthSettings auth) {
        return auth.getUsername() != null && auth.getPasswordHash() != null;
    }

    /** Session authentication for the admin described by {@code auth}, which must have credentials. */
    public static Authentication create(AuthSettings auth) {
        var token = UsernamePasswordAuthenticationToken.authenticated(auth.getUsername(), null,
                List.of(new SimpleGrantedAuthority(ROLE)));
        token.setDetails(CredentialStamp.of(auth));
        return token;
    }

    /** True if {@code authentication} is a session issued for the credentials currently in {@code auth}. */
    public static boolean isAdmin(Authentication authentication, AuthSettings auth) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken || !hasCredentials(auth)) {
            return false;
        }
        return auth.getUsername().equals(authentication.getName())
                && authentication.getDetails() instanceof CredentialStamp stamp
                && stamp.equals(CredentialStamp.of(auth));
    }
}
