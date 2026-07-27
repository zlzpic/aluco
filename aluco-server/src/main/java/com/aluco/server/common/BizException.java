package com.aluco.server.common;

import org.springframework.http.HttpStatus;
/**
 * Business exception carrying a stable error code and HTTP status.
 * Rendered by the API layer as { "code": ..., "message": ... } (spec 5.5).
 */
public class BizException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BizException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static BizException notFound(String code, String message) {
        return new BizException(code, message, HttpStatus.NOT_FOUND);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(code, message, HttpStatus.CONFLICT);
    }

    public static BizException badRequest(String code, String message) {
        return new BizException(code, message, HttpStatus.BAD_REQUEST);
    }

    public static BizException unauthorized(String code, String message) {
        return new BizException(code, message, HttpStatus.UNAUTHORIZED);
    }
}
