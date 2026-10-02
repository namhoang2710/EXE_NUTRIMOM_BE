package vn.nutrimom.calendar.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.user.repository.UserPreferenceRepository;

/**
 * Chọn múi giờ cho một lượt xem lịch, và quy đổi mốc giờ của buổi tư vấn.
 *
 * <p>{@link #VIETNAM_ZONE} nhân bản có chủ đích hằng cùng tên trong
 * {@code vn.nutrimom.consultation.service.ConsultationClock} — hằng đó package-private nên module
 * này không gọi được, và nới visibility của module khác chỉ vì một hằng thì đắt hơn là chép lại.
 * Hai chỗ phải đổi cùng nhau nếu quy ước "slot lưu theo giờ VN" thay đổi.</p>
 */
@Component
public class CalendarZone {

    /**
     * Múi giờ ngầm định của {@code consultation_slots}: {@code slot_date}/{@code start_time} là giá
     * trị naive mang nghĩa giờ Việt Nam, không phải giờ của người đang xem.
     */
    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserPreferenceRepository preferences;
    private final CalendarProperties properties;

    public CalendarZone(UserPreferenceRepository preferences, CalendarProperties properties) {
        this.preferences = preferences;
        this.properties = properties;
    }

    /**
     * Múi giờ để dựng cửa sổ truy vấn và để xếp mốc vào ô ngày nào: tham số của request → tuỳ chọn
     * của người dùng → mặc định cấu hình.
     *
     * @throws BusinessException {@code VALIDATION_ERROR} nếu client gửi chuỗi múi giờ không có thật.
     *         Không bắt thì {@link ZoneId#of} ném {@code DateTimeException} — không phải lỗi binding
     *         của Spring nên sẽ rơi vào handler catch-all và thành 500.
     */
    public ZoneId resolve(String requested, String userId) {
        if (requested != null && !requested.isBlank()) {
            return parseOrReject(requested.trim());
        }
        String preferred = preferences.findById(userId)
                .map(preference -> preference.getTimezone())
                .filter(value -> value != null && !value.isBlank())
                .orElse(properties.getDefaultTimezone());
        // Tuỳ chọn đã lưu không phải do request này gửi, nên nó hỏng thì rơi về mặc định chứ không
        // được làm hỏng cả màn hình lịch của người dùng.
        try {
            return ZoneId.of(preferred);
        } catch (DateTimeException ex) {
            return ZoneId.of(properties.getDefaultTimezone());
        }
    }

    private ZoneId parseOrReject(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Múi giờ không hợp lệ: " + value + ".");
        }
    }
}
