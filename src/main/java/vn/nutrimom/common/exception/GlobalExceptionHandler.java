package vn.nutrimom.common.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vn.nutrimom.common.api.ApiError;
import vn.nutrimom.common.api.ApiErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex) {
        ApiError body = new ApiError(ex.getCode(), ex.getMessage(), ex.getFields(),
                ex.isRetryable(), MDC.get("requestId"));
        return ResponseEntity.status(ex.getStatus()).body(new ApiErrorResponse(body));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return error(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fields.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        return error(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), fields);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, String> fields = Map.of(ex.getParameterName(), "Tham số bắt buộc bị thiếu.");
        return error(ErrorCode.INVALID_REQUEST_PARAMETER,
                ErrorCode.INVALID_REQUEST_PARAMETER.defaultMessage(), fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, String> fields = Map.of(ex.getName(), "Giá trị không đúng kiểu dữ liệu.");
        return error(ErrorCode.INVALID_REQUEST_PARAMETER,
                ErrorCode.INVALID_REQUEST_PARAMETER.defaultMessage(), fields);
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiErrorResponse> handleMultipart(Exception ex) {
        return error(ErrorCode.INVALID_MULTIPART);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleContentType(HttpMediaTypeNotSupportedException ex) {
        return error(ErrorCode.INVALID_CONTENT_TYPE);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return error(ErrorCode.VERSION_CONFLICT);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex) {
        return error(ErrorCode.MALFORMED_JSON);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return error(ErrorCode.FILE_TOO_LARGE);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex) {
        return error(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException ex) {
        return error(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return error(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled request error", ex);
        return error(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ApiErrorResponse> error(ErrorCode error) {
        return error(error, error.defaultMessage(), Map.of());
    }

    private ResponseEntity<ApiErrorResponse> error(ErrorCode error, String message,
                                                   Map<String, String> fields) {
        ApiError body = new ApiError(error.code(), message, fields, error.retryable(),
                MDC.get("requestId"));
        return ResponseEntity.status(error.status()).body(new ApiErrorResponse(body));
    }
}
