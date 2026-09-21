package vn.nutrimom.consultation.domain;

/**
 * Vòng đời của một yêu cầu tư vấn.
 *
 * <ul>
 *   <li>{@code PENDING_EXPERT}: "đang chờ chuyên gia" — yêu cầu RANDOM chưa có chuyên gia nào nhận.</li>
 *   <li>{@code PENDING_CONSULTATION}: "đang chờ tư vấn" — đã có chuyên gia + khung giờ, chờ tới buổi tư vấn.</li>
 *   <li>{@code COMPLETED}: "đã tư vấn" — chuyên gia đã đánh dấu hoàn thành; user có thể đánh giá.</li>
 *   <li>{@code CANCELLED}: user hủy trước khi tư vấn.</li>
 * </ul>
 */
public enum ConsultationStatus {
    PENDING_EXPERT,
    PENDING_CONSULTATION,
    COMPLETED,
    CANCELLED
}
