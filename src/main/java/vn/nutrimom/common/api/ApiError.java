package vn.nutrimom.common.api;

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> fields,
                       boolean retryable, String requestId) {
}
