package vn.nutrimom.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final Map<String, String> fields;

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, false);
    }

    public BusinessException(HttpStatus status, String code, String message, boolean retryable) {
        this(status, code, message, retryable, Map.of());
    }

    public BusinessException(HttpStatus status, String code, String message,
                             boolean retryable, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    public BusinessException(ErrorCode error) {
        this(error, error.defaultMessage());
    }

    public BusinessException(ErrorCode error, String message) {
        this(error.status(), error.code(), message, error.retryable(), Map.of());
    }

    public BusinessException(ErrorCode error, String message, Map<String, String> fields) {
        this(error.status(), error.code(), message, error.retryable(), fields);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
    public Map<String, String> getFields() { return fields; }
}
