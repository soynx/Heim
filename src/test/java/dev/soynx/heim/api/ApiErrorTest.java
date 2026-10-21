package dev.soynx.heim.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class ApiErrorTest {

    @Test
    void codesAreLowercaseKebab() {
        assertThat(ApiError.code(HttpStatus.BAD_REQUEST)).isEqualTo("bad-request");
        assertThat(ApiError.code(HttpStatus.UNAUTHORIZED)).isEqualTo("unauthorized");
        assertThat(ApiError.code(HttpStatus.FORBIDDEN)).isEqualTo("forbidden");
        assertThat(ApiError.code(HttpStatus.NOT_FOUND)).isEqualTo("not-found");
        assertThat(ApiError.code(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo("method-not-allowed");
        assertThat(ApiError.code(HttpStatus.CONFLICT)).isEqualTo("conflict");
        assertThat(ApiError.code(HttpStatusCode.valueOf(413))).isEqualTo("payload-too-large");
        assertThat(ApiError.code(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo("internal-server-error");
        assertThat(ApiError.code(HttpStatusCode.valueOf(599))).isEqualTo("http-599");
    }
}
