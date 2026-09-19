package vn.nutrimom.common.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.MDC;

public final class ApiResponses {
    private ApiResponses() {
    }

    public static <T> ApiResponse<T> success(T data) {
        return success(data, List.of());
    }

    public static <T> ApiResponse<T> success(T data, List<String> partialFailures) {
        return new ApiResponse<>(data,
                new ApiMeta(MDC.get("requestId"), OffsetDateTime.now(ZoneOffset.UTC),
                        partialFailures == null ? List.of() : List.copyOf(partialFailures)));
    }
}
