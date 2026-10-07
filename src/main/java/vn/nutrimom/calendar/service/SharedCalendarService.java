package vn.nutrimom.calendar.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarEventItem;
import vn.nutrimom.calendar.dto.SharedCalendarDtos.SharedCalendarEventsResponse;
import vn.nutrimom.calendar.dto.SharedCalendarDtos.SharedCalendarMonthResponse;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.service.FamilySharingResolver;
import vn.nutrimom.family.service.FamilySharingResolver.SharedContext;

/**
 * Lịch của mẹ bầu mở cho thành viên gia đình giữ {@link FamilyScope#SHARED_CALENDAR}.
 *
 * <p>Toàn bộ việc phân quyền gói trong {@link #ALLOWED_SOURCES} và
 * {@link CalendarQueryService#resolveSources}: người xem gửi {@code types} gì cũng chỉ thu hẹp
 * được tập này, không bao giờ nới ra.</p>
 */
@Service
public class SharedCalendarService {

    /**
     * Nguồn người nhà được đọc.
     *
     * <p>{@link CalendarSource#MEDICAL_RECORD} CỐ Ý vắng mặt, kể cả khi thành viên giữ
     * {@link FamilyScope#MEDICAL_RECORDS}. Scope đó hiện chưa được {@code MedicalRecordService} thực
     * thi, nên mở hồ sơ y tế qua đường lịch sẽ cho ra một dòng bấm vào là 404; tệ hơn, {@code title}
     * của hồ sơ do mẹ tự gõ nên chẩn đoán rò ngay ở tiêu đề mà không cần mở bản ghi. Khi nào
     * {@code MEDICAL_RECORDS} được thực thi tử tế ở chính module của nó thì mới thêm nguồn này.</p>
     */
    private static final Set<CalendarSource> ALLOWED_SOURCES =
            Set.of(CalendarSource.REMINDER, CalendarSource.CONSULTATION);

    private final FamilySharingResolver sharing;
    private final CalendarQueryService calendar;
    private final CalendarZone zones;
    private final UserRepository users;

    public SharedCalendarService(FamilySharingResolver sharing,
                                 CalendarQueryService calendar,
                                 CalendarZone zones,
                                 UserRepository users) {
        this.sharing = sharing;
        this.calendar = calendar;
        this.zones = zones;
        this.users = users;
    }

    /**
     * @param timezone múi giờ của NGƯỜI XEM, không phải của mẹ: {@code date} là "ô lịch nào" nên
     *                 phải theo người đang nhìn màn hình. {@code startsAt} vẫn là mốc tuyệt đối.
     */
    @Transactional(readOnly = true)
    public SharedCalendarEventsResponse events(String viewerUserId, LocalDate from, LocalDate to,
                                               String timezone, Set<CalendarSource> types) {
        SharedContext context = sharing.requireScope(viewerUserId, FamilyScope.SHARED_CALENDAR);
        ZoneId zone = zones.resolve(timezone, viewerUserId);
        List<CalendarEventItem> events = calendar.eventsFor(
                context.ownerUserId(), from, to, zone, types, ALLOWED_SOURCES);
        return new SharedCalendarEventsResponse(context.group().getId(), ownerName(context),
                allowedSources(), readOnly(events));
    }

    @Transactional(readOnly = true)
    public SharedCalendarMonthResponse month(String viewerUserId, int year, int month,
                                             String timezone) {
        SharedContext context = sharing.requireScope(viewerUserId, FamilyScope.SHARED_CALENDAR);
        ZoneId zone = zones.resolve(timezone, viewerUserId);
        return new SharedCalendarMonthResponse(context.group().getId(), ownerName(context),
                allowedSources(),
                calendar.monthFor(context.ownerUserId(), year, month, zone, ALLOWED_SOURCES));
    }

    /**
     * Mốc sắp tới cho dashboard người nhà.
     *
     * <p>Nhận sẵn {@code context} vì dashboard đã phân giải membership một lần rồi — gọi lại
     * {@code requireScope} ở đây là một vòng truy vấn thừa cho cùng một câu trả lời.</p>
     */
    @Transactional(readOnly = true)
    public List<CalendarEventItem> upcomingFor(SharedContext context, String viewerUserId,
                                               int limit) {
        return readOnly(calendar.upcomingFor(context.ownerUserId(),
                zones.resolve(null, viewerUserId), ALLOWED_SOURCES, limit));
    }

    /**
     * Bỏ {@code deepLink} khỏi mốc lịch trước khi trả cho người nhà.
     *
     * <p>{@link CalendarQueryService} dựng deep link theo góc nhìn chủ sở hữu
     * ({@code nutrimom://calendar/reminders/...}, {@code nutrimom://consultations/...}), và những
     * endpoint đó chỉ mở cho chính chủ. Phát link ấy ra cho thành viên gia đình là mời họ bấm vào
     * một màn hình 403 — nên cắt ở đây, thay vì nới quyền endpoint của chủ sở hữu.</p>
     *
     * <p>Null chứ không xoá field: {@code CalendarEventItem} dùng chung với màn hình của chính chủ
     * (vốn vẫn cần deep link), và Jackson đang để {@code non_null} nên {@code deep_link} biến mất
     * hẳn khỏi JSON của người nhà thay vì hiện ra dưới dạng null.</p>
     *
     * <p>Đặt ở lớp này để cả hai bề mặt của người nhà — {@code /family/shared-calendar/events} và
     * {@code /dashboard/partner} qua {@link #upcomingFor} — đi qua cùng một chỗ. {@link #month}
     * không cần: {@code CalendarMonthDay} chưa bao giờ mang deep link.</p>
     */
    private static List<CalendarEventItem> readOnly(List<CalendarEventItem> events) {
        return events.stream()
                .map(event -> new CalendarEventItem(event.source(), event.sourceId(), event.title(),
                        event.subtitle(), event.startsAt(), event.endsAt(), event.date(),
                        event.status(), null, event.recurring()))
                .toList();
    }

    /** Thứ tự khai báo enum, để client nhận được mảng ổn định giữa hai lần gọi. */
    private static List<CalendarSource> allowedSources() {
        return List.copyOf(EnumSet.copyOf(ALLOWED_SOURCES));
    }

    private String ownerName(SharedContext context) {
        return users.findById(context.ownerUserId())
                .map(user -> user.getDisplayName())
                .orElse(null);
    }
}
