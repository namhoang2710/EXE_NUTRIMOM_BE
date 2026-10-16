package vn.nutrimom.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore, String realtimeRevision) {
    public CursorPage(List<T> items, String nextCursor, boolean hasMore) {
        this(items,nextCursor,hasMore,null);
    }
    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
