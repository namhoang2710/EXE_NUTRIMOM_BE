package vn.nutrimom.common.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.MDC;

public final class ApiResponses {
    private ApiResponses() {
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data,
                new ApiMeta(MDC.get("requestId"), OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
