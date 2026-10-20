package dev.soynx.heim.api;

/** JSON body of every error response. */
public record ApiError(String error, String message) {
}
