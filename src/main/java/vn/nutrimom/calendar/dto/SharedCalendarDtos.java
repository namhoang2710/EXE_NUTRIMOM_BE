package vn.nutrimom.calendar.dto;

import java.util.List;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarEventItem;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarMonthDay;

/**
 * Lịch của chủ thai kỳ nhìn từ phía người nhà.
 *
 * <p>Response bọc thêm {@code allowedSources} thay vì trả thẳng danh sách mốc: nếu client không
 * biết nguồn nào bị cắt, nó sẽ vẽ chip lọc "Hồ sơ y tế" rồi lọc ra rỗng mà không hiểu vì sao.</p>
 *
 * <p>Cố ý KHÔNG mang {@code ownerUserId}, số điện thoại, email hay {@code pregnancyId} — người nhà
 * chỉ cần một cái tên để đặt tiêu đề màn hình.</p>
 */
public final class SharedCalendarDtos {

    private SharedCalendarDtos() {
    }

    public record SharedCalendarEventsResponse(
            String familyGroupId,
            String ownerDisplayName,
            List<CalendarSource> allowedSources,
            List<CalendarEventItem> events) {
    }

    public record SharedCalendarMonthResponse(
            String familyGroupId,
            String ownerDisplayName,
            List<CalendarSource> allowedSources,
            List<CalendarMonthDay> days) {
    }
}
