package vn.nutrimom.consultation.domain;

/**
 * Cách user chọn chuyên gia khi tạo yêu cầu tư vấn.
 *
 * <ul>
 *   <li>{@code DIRECT}: user tự chọn chuyên gia + khung giờ trống.</li>
 *   <li>{@code RANDOM}: user chỉ chọn chuyên khoa; yêu cầu vào pool cho mọi chuyên gia
 *       khoa đó, ai rảnh sẽ nhận và tự xếp vào slot trống của mình.</li>
 * </ul>
 */
public enum AssignmentType {
    DIRECT,
    RANDOM
}
