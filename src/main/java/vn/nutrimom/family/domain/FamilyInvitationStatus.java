package vn.nutrimom.family.domain;

/**
 * Vòng đời của một lời mời.
 *
 * <p>Không có {@code EXPIRED}: hết hạn là so sánh {@code expires_at} với hiện tại, không phải một
 * trạng thái được ghi xuống. Lưu nó thành giá trị sẽ đẻ ra một job quét chỉ để giữ cột khỏi sai.</p>
 */
public enum FamilyInvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED
}
