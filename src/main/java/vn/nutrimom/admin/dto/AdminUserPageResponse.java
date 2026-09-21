package vn.nutrimom.admin.dto;

import java.util.List;

public record AdminUserPageResponse(
        List<AdminUserListItemResponse> items,
        int page,
        int pageSize,
        long totalItems,
        int totalPages) {
}
