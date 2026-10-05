package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Lời mời nhìn từ phía người được mời, trước khi họ bấm chấp nhận.
 *
 * <p>Cố ý KHÔNG mang {@code id}, {@code family_group_id}, {@code pregnancy_id},
 * {@code owner_user_id}, token hay địa chỉ đầy đủ — chỉ vừa đủ để người ta biết mình đang đồng ý
 * cho ai xem những gì.</p>
 *
 * @param maskedTarget địa chỉ/số đã che. Có mặt để giao diện nhắc được "hãy đăng nhập bằng tài
 *                     khoản này", vì {@code accept} vẫn đòi tài khoản khớp đúng email/sđt.
 * @param status       PENDING / ACCEPTED / REVOKED / EXPIRED — EXPIRED suy ra lúc đọc, không lưu DB.
 */
public record FamilyInvitationPreviewResponse(
        String inviterDisplayName,
        String relationship,
        String relationshipLabel,
        List<String> scopes,
        List<String> scopeLabels,
        String targetType,
        String maskedTarget,
        OffsetDateTime expiresAt,
        String status) { }
