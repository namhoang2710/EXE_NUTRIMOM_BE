package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record FamilyInvitationResponse(
        String id,
        String familyGroupId,
        String invitedPhone,
        String invitedEmail,
        String token,
        /** Đích của nút "Chấp nhận lời mời"; chứa chính {@code token} nên bảo mật như nhau. */
        String inviteUrl,
        String relationship,
        List<String> scopes,
        String status,
        /** SENT / FAILED / SKIPPED — client dựa vào đây để quyết định có hiện nút copy link không. */
        String deliveryStatus,
        OffsetDateTime sentAt,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) { }
