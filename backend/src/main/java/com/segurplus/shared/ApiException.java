package com.segurplus.shared;

import org.springframework.http.HttpStatus;

/** Error de API que se traduce a un RFC 7807 Problem Details. El {@code detail} es genérico: nunca revela por qué se denegó. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** Sufijo del {@code type}: {@code urn:segurplus:problem:<code>}. */
    public String code() {
        return code;
    }

    /** 404 idéntico para «no existe» y «no autorizado» (no revela existencia). */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "The requested resource was not found.");
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad-request", detail);
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication is required or the credentials are invalid.");
    }

    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "The operation is not permitted for this user.");
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", detail);
    }

    public static ApiException unsupportedMedia(String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", detail);
    }

    public static ApiException payloadTooLarge(String detail) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", detail);
    }
}
