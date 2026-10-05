package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Một dòng trong danh sách lời mời của chủ nhóm.
 *
 * <p>Không có {@code token} lẫn {@code invite_url}: token thô chỉ tồn tại đúng một lần trong
 * response lúc tạo, hệ thống chỉ giữ bản băm nên không dựng lại được — và cũng không nên.</p>
 */
public record FamilyInvitationSummaryResponse(
        String id,
        String targetType,
        String maskedTarget,
        String relationship,
        List<String> scopes,
        String status,
        String deliveryStatus,
        OffsetDateTime sentAt,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) { }
