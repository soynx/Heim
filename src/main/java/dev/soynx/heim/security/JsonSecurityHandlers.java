package dev.soynx.heim.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.soynx.heim.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 401/403 as {@link ApiError} JSON: no redirects and no {@code WWW-Authenticate}, so no browser popup. */
class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    JsonSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException e) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, new ApiError("unauthorized", "Login required"));
    }

    /**
     * CSRF failures are 403. Any other denial means the caller is not the current admin (for example a session
     * issued before the password changed), which the client must treat like being logged out: 401.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException e) throws IOException {
        if (e instanceof CsrfException) {
            write(response, HttpStatus.FORBIDDEN, new ApiError("forbidden", "Missing or invalid CSRF token"));
        } else {
            write(response, HttpStatus.UNAUTHORIZED, new ApiError("unauthorized", "Login required"));
        }
    }

    private void write(HttpServletResponse response, HttpStatus status, ApiError error) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), error);
    }
}
