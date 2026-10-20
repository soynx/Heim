package dev.soynx.heim.api.dto;

/** Body of {@code POST /api/auth/login}. */
public record AuthLoginRequest(String username, String password) {
}
