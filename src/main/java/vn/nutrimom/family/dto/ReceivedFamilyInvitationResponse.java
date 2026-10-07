package vn.nutrimom.family.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Một dòng trong hộp thư "lời mời gửi cho tôi".
 *
 * <p>Cố ý KHÔNG dùng lại {@link FamilyInvitationSummaryResponse}: bản đó mang
 * {@code deliveryStatus} và {@code sentAt} — hai thứ thuộc về vận hành của chủ nhóm ("SMTP của tôi
 * chạy không?"). Người được mời không có việc gì phải biết máy chủ mail của người khác thành công
 * hay không, và {@code sent_at} là một kênh phụ hé lộ hạ tầng của họ.</p>
 *
 * <p>Ngược lại, bản này mang {@code id} (để gọi {@code /{id}/preview} và {@code /{id}/accept}) và
 * {@code inviterDisplayName} + nhãn tiếng Việt — những thứ một dòng hộp thư cần mà bản tóm tắt
 * phía chủ nhóm không có.</p>
 *
 * @param maskedTarget giữ lại dù người nhận biết địa chỉ của chính mình: nó cho biết <em>danh tính
 *                     nào</em> được mời (email hay số điện thoại), và vốn đã được che.
 * @param status       luôn là {@code PENDING} ở danh sách này — hết hạn và đã thu hồi bị lọc ở
 *                     truy vấn. Vẫn trả về để client dùng chung một bộ render với màn chi tiết.
 */
public record ReceivedFamilyInvitationResponse(
        String id,
        String inviterDisplayName,
        String relationship,
        String relationshipLabel,
        List<String> scopes,
        List<String> scopeLabels,
        String targetType,
        String maskedTarget,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) { }
