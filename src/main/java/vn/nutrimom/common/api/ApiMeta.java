package vn.nutrimom.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiMeta(String requestId, OffsetDateTime serverTime,
                      List<String> partialFailures) {
    public ApiMeta(String requestId, OffsetDateTime serverTime) {
        this(requestId, serverTime, List.of());
    }
}
