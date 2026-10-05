package vn.nutrimom.calendar.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.domain.CalendarReminderOccurrenceEntity;
import vn.nutrimom.calendar.domain.OccurrenceStatus;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarEventItem;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarMonthDay;
import vn.nutrimom.calendar.dto.ConfirmedConsultationRow;
import vn.nutrimom.calendar.dto.MedicalRecordCalendarRow;
import vn.nutrimom.calendar.repository.CalendarConsultationQueryRepository;
import vn.nutrimom.calendar.repository.CalendarMedicalRecordQueryRepository;
import vn.nutrimom.calendar.repository.CalendarReminderOccurrenceRepository;
import vn.nutrimom.calendar.repository.CalendarReminderRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;

/**
 * Dòng thời gian đã trộn của một người dùng (spec mục 10).
 *
 * <p>Lịch chỉ ĐỌC hai nguồn sẵn có — hồ sơ y tế và buổi tư vấn đã được chuyên gia xác nhận — và
 * không nhân bản chúng sang bảng riêng: hai bản sự thật thì sớm muộn cũng lệch nhau. Nguồn thứ ba,
 * nhắc nhở người dùng tự tạo, do {@link CalendarReminderService} quản lý.</p>
 *
 * <p>Chủ sở hữu đi qua {@link #events}/{@link #month}; thành viên gia đình có
 * {@code FamilyScope.SHARED_CALENDAR} đi qua {@link #eventsFor}/{@link #monthFor} với tập nguồn
 * do {@code SharedCalendarService} dẫn xuất. Hai lối vào dùng chung {@code collect}, khác nhau duy
 * nhất ở chỗ id truy vấn là chủ thai kỳ chứ không phải người đang xem.</p>
 */
@Service
public class CalendarQueryService {

    /**
     * Buổi tư vấn lên lịch khi đã có khung giờ. Yêu cầu còn {@code PENDING_EXPERT} chưa có chuyên
     * gia nhận nên chưa có mốc giờ nào để vẽ; yêu cầu {@code CANCELLED} đã được trả {@code slot_id}
     * về null lúc huỷ nên tự rụng khỏi join — lọc theo status ở đây là chốt chặn thứ hai.
     */
    private static final Set<ConsultationStatus> CALENDAR_STATUSES =
            EnumSet.of(ConsultationStatus.PENDING_CONSULTATION, ConsultationStatus.COMPLETED);

    /** Lối vào của chính chủ: không bị cắt nguồn nào. */
    private static final Set<CalendarSource> ALL_SOURCES =
            Set.copyOf(EnumSet.allOf(CalendarSource.class));

    /** Nhãn tiếng Việt của loại hồ sơ, dùng làm dòng phụ của ô lịch. */
    private static final Map<MedicalRecordCategory, String> CATEGORY_LABELS = Map.of(
            MedicalRecordCategory.PRENATAL_VISIT, "Khám thai",
            MedicalRecordCategory.ULTRASOUND, "Siêu âm",
            MedicalRecordCategory.LAB_RESULT, "Kết quả xét nghiệm",
            MedicalRecordCategory.PRESCRIPTION, "Đơn thuốc",
            MedicalRecordCategory.DISCHARGE, "Giấy ra viện",
            MedicalRecordCategory.OTHER, "Hồ sơ y tế");

    private final CalendarMedicalRecordQueryRepository medicalRecords;
    private final CalendarConsultationQueryRepository consultations;
    private final CalendarReminderRepository reminders;
    private final CalendarReminderOccurrenceRepository occurrences;
    private final CalendarZone zones;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarQueryService(CalendarMedicalRecordQueryRepository medicalRecords,
                                CalendarConsultationQueryRepository consultations,
                                CalendarReminderRepository reminders,
                                CalendarReminderOccurrenceRepository occurrences,
                                CalendarZone zones,
                                CalendarProperties properties,
                                Clock clock) {
        this.medicalRecords = medicalRecords;
        this.consultations = consultations;
        this.reminders = reminders;
        this.occurrences = occurrences;
        this.zones = zones;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Danh sách đã trộn trong khoảng ngày {@code [from, to]} (bao gồm hai đầu), hiểu theo
     * {@code timezone}.
     *
     * <p>Không phân trang: cửa sổ bị chặn trần {@code app.calendar.max-range-days} nên số mốc của
     * một người luôn hữu hạn, và một màn hình lịch vốn phải vẽ trọn khoảng đang xem.</p>
     *
     * @param types lọc theo nguồn; null/rỗng = lấy cả ba.
     */
    @Transactional(readOnly = true)
    public List<CalendarEventItem> events(String userId, LocalDate from, LocalDate to,
                                          String timezone, Set<CalendarSource> types) {
        requireRange(from, to);
        return eventsFor(userId, from, to, zones.resolve(timezone, userId), types, ALL_SOURCES);
    }

    /** Lưới tháng: chỉ những ngày có ít nhất một mốc (spec mục 10 "response gọn"). */
    @Transactional(readOnly = true)
    public List<CalendarMonthDay> month(String userId, int year, int month, String timezone) {
        return monthIn(userId, yearMonth(year, month), zones.resolve(timezone, userId), ALL_SOURCES);
    }

    private List<CalendarMonthDay> monthIn(String ownerUserId, YearMonth target, ZoneId zone,
                                           Set<CalendarSource> sources) {
        List<CalendarEventItem> items = collect(ownerUserId,
                target.atDay(1).atStartOfDay(zone).toOffsetDateTime(),
                target.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime(), zone, sources);

        // LinkedHashMap: items đã sắp theo thời gian nên ngày cũng ra theo thứ tự tăng dần.
        Map<LocalDate, EnumSet<CalendarSource>> byDate = new LinkedHashMap<>();
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (CalendarEventItem item : items) {
            byDate.computeIfAbsent(item.date(), key -> EnumSet.noneOf(CalendarSource.class))
                    .add(item.source());
            counts.merge(item.date(), 1, Integer::sum);
        }
        // EnumSet duyệt theo thứ tự khai báo enum nên types[] ổn định giữa hai lần gọi.
        return byDate.entrySet().stream()
                .map(entry -> new CalendarMonthDay(entry.getKey(), counts.get(entry.getKey()),
                        List.copyOf(entry.getValue())))
                .toList();
    }

    /**
     * Mốc sắp tới gần nhất cho {@code next_appointment} của dashboard mẹ bầu (spec mục 05).
     *
     * <p>Chỉ xét buổi tư vấn và nhắc nhở: hồ sơ y tế ghi lại việc đã xảy ra, không phải hẹn sắp tới.</p>
     */
    @Transactional(readOnly = true)
    public CalendarEventItem nextAppointment(String userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ZoneId zone = zones.resolve(null, userId);
        return collect(userId, now, now.plusDays(properties.getDashboardLookaheadDays()), zone,
                EnumSet.of(CalendarSource.CONSULTATION, CalendarSource.REMINDER))
                .stream().findFirst().orElse(null);
    }

    /** {@code upcoming_reminders} của dashboard mẹ bầu. */
    @Transactional(readOnly = true)
    public List<CalendarEventItem> upcomingReminders(String userId, int limit) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ZoneId zone = zones.resolve(null, userId);
        return collect(userId, now, now.plusDays(properties.getDashboardLookaheadDays()), zone,
                EnumSet.of(CalendarSource.REMINDER))
                .stream().limit(limit).toList();
    }

    // ----- Lối vào cho thành viên gia đình (FamilyScope.SHARED_CALENDAR) -----

    /**
     * Lịch của {@code ownerUserId} nhìn bằng múi giờ {@code zone} của người xem.
     *
     * <p>{@code requested} đến từ query param của người xem, {@code allowed} do server dẫn xuất từ
     * scope. Hai thứ này KHÔNG được trộn trước khi vào {@link #resolveSources} — đó là chỗ duy nhất
     * quyết định người xem thật sự được đọc nguồn nào.</p>
     */
    @Transactional(readOnly = true)
    public List<CalendarEventItem> eventsFor(String ownerUserId, LocalDate from, LocalDate to,
                                             ZoneId zone, Set<CalendarSource> requested,
                                             Set<CalendarSource> allowed) {
        requireRange(from, to);
        return collect(ownerUserId, from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), zone,
                resolveSources(requested, allowed));
    }

    @Transactional(readOnly = true)
    public List<CalendarMonthDay> monthFor(String ownerUserId, int year, int month, ZoneId zone,
                                           Set<CalendarSource> allowed) {
        return monthIn(ownerUserId, yearMonth(year, month), zone, resolveSources(null, allowed));
    }

    /** Mốc sắp tới của chủ thai kỳ, cho khối lịch trên dashboard người nhà. */
    @Transactional(readOnly = true)
    public List<CalendarEventItem> upcomingFor(String ownerUserId, ZoneId zone,
                                               Set<CalendarSource> allowed, int limit) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return collect(ownerUserId, now, now.plusDays(properties.getDashboardLookaheadDays()), zone,
                resolveSources(null, allowed))
                .stream().limit(limit).toList();
    }

    /**
     * Tập nguồn thật sự được đọc: {@code requested} null/rỗng nghĩa là lấy trọn {@code allowed},
     * ngược lại là phần GIAO của hai tập.
     *
     * <p>Giao rỗng trả về rỗng. Đây là điểm mấu chốt: bản cũ coi "không lọc gì" đồng nghĩa "lấy tất
     * cả", nên nếu giữ nguyên nếp đó thì một người xem chỉ được cấp nhắc nhở chỉ cần gửi
     * {@code types=MEDICAL_RECORD} là giao thành rỗng rồi lại mở toang cả ba nguồn.</p>
     */
    static Set<CalendarSource> resolveSources(Set<CalendarSource> requested,
                                              Set<CalendarSource> allowed) {
        EnumSet<CalendarSource> effective = EnumSet.noneOf(CalendarSource.class);
        effective.addAll(allowed);
        if (requested != null && !requested.isEmpty()) {
            effective.retainAll(requested);
        }
        return effective;
    }

    // ----- Trộn -----

    /**
     * @param to      biên trên LOẠI TRỪ — một mốc đúng 00:00 ngày kế tiếp thuộc về cửa sổ sau.
     * @param sources tập nguồn ĐÃ CHỐT, không bao giờ null; rỗng nghĩa là không đọc gì cả.
     */
    private List<CalendarEventItem> collect(String ownerUserId, OffsetDateTime from,
                                            OffsetDateTime to, ZoneId zone,
                                            Set<CalendarSource> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        List<CalendarEventItem> items = new ArrayList<>();
        if (sources.contains(CalendarSource.MEDICAL_RECORD)) {
            medicalRecords.findWindow(ownerUserId, from, to).stream()
                    .map(row -> toItem(row, zone)).forEach(items::add);
        }
        if (sources.contains(CalendarSource.CONSULTATION)) {
            items.addAll(consultationItems(ownerUserId, from, to, zone));
        }
        if (sources.contains(CalendarSource.REMINDER)) {
            items.addAll(reminderItems(ownerUserId, from, to, zone));
        }
        if (items.size() > properties.getMaxEventsPerResponse()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khoảng xem có quá nhiều mốc (" + items.size() + "). Vui lòng thu hẹp lại.");
        }
        items.sort(Comparator.comparing(CalendarEventItem::startsAt)
                .thenComparing(CalendarEventItem::source)
                .thenComparing(CalendarEventItem::sourceId));
        return List.copyOf(items);
    }

    /**
     * Nhắc nhở trong cửa sổ, đã khai triển các lần lặp.
     *
     * <p>Một dòng lặp hằng ngày sinh ra mỗi ngày một mốc — các mốc này <strong>không</strong> được
     * lưu trong DB, chúng được tính ra ở đây mỗi lần vẽ lịch. Trạng thái "đã uống / bỏ qua" của
     * từng mốc được nạp một lượt cho tất cả nhắc nhở đang hiện, thay vì hỏi lẻ từng mốc.</p>
     */
    private List<CalendarEventItem> reminderItems(String userId, OffsetDateTime from,
                                                  OffsetDateTime to, ZoneId zone) {
        List<CalendarReminderEntity> rows = reminders.findWindow(userId, from, to,
                from.atZoneSameInstant(zone).toLocalDate());
        if (rows.isEmpty()) {
            return List.of();
        }
        // Khoá theo Instant chứ không theo OffsetDateTime: mốc đọc từ DB mang offset UTC còn mốc
        // tính ra mang offset của múi giờ nhắc nhở, equals() của OffsetDateTime so cả offset nên
        // hai thứ cùng thời điểm vẫn không khớp nhau.
        Map<String, Map<Instant, OccurrenceStatus>> marked = occurrences
                .findWindow(rows.stream().map(CalendarReminderEntity::getId).toList(), from, to)
                .stream()
                .collect(Collectors.groupingBy(CalendarReminderOccurrenceEntity::getReminderId,
                        Collectors.toMap(entry -> entry.getOccurrenceAt().toInstant(),
                                CalendarReminderOccurrenceEntity::getStatus,
                                (first, second) -> second)));

        List<CalendarEventItem> items = new ArrayList<>();
        for (CalendarReminderEntity reminder : rows) {
            ReminderSchedule schedule = ReminderSchedule.of(reminder);
            Map<Instant, OccurrenceStatus> statuses =
                    marked.getOrDefault(reminder.getId(), Map.of());
            for (OffsetDateTime at : schedule.occurrencesBetween(from, to)) {
                OccurrenceStatus marker = statuses.get(at.toInstant());
                String status = marker != null ? marker.name() : reminder.getStatus().name();
                items.add(toItem(reminder, at, zone, status, schedule.repeats()));
            }
        }
        return items;
    }

    /**
     * Buổi tư vấn lưu ngày/giờ dạng naive theo giờ Việt Nam, còn cửa sổ truy vấn là mốc tuyệt đối
     * dựng từ múi giờ người xem. Vì vậy query nới biên ra ±1 ngày rồi lọc chính xác ở đây sau khi
     * quy đổi — lọc khít {@code slot_date} ở DB sẽ rụng mất nửa ngày với mọi múi giờ khác VN.
     */
    private List<CalendarEventItem> consultationItems(String userId, OffsetDateTime from,
                                                      OffsetDateTime to, ZoneId zone) {
        LocalDate fromDate = from.atZoneSameInstant(CalendarZone.VIETNAM_ZONE).toLocalDate().minusDays(1);
        LocalDate toDate = to.atZoneSameInstant(CalendarZone.VIETNAM_ZONE).toLocalDate().plusDays(1);
        return consultations.findConfirmed(userId, CALENDAR_STATUSES, fromDate, toDate).stream()
                .map(row -> toItem(row, zone))
                .filter(item -> !item.startsAt().isBefore(from) && item.startsAt().isBefore(to))
                .toList();
    }

    private static CalendarEventItem toItem(MedicalRecordCalendarRow row, ZoneId zone) {
        String label = CATEGORY_LABELS.get(row.category());
        String subtitle = row.facilityName() == null || row.facilityName().isBlank()
                ? label : label + " · " + row.facilityName();
        return new CalendarEventItem(CalendarSource.MEDICAL_RECORD, row.recordId(), row.title(),
                subtitle, row.occurredAt(), null,
                row.occurredAt().atZoneSameInstant(zone).toLocalDate(), null,
                "nutrimom://medical-records/" + row.recordId(), false);
    }

    private static CalendarEventItem toItem(ConfirmedConsultationRow row, ZoneId zone) {
        OffsetDateTime startsAt = row.slotDate().atTime(row.startTime())
                .atZone(CalendarZone.VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime endsAt = row.slotDate().atTime(row.endTime())
                .atZone(CalendarZone.VIETNAM_ZONE).toOffsetDateTime();
        String subtitle = row.expertFullName() == null ? "Buổi tư vấn 1-1"
                : "Chuyên gia " + row.expertFullName();
        return new CalendarEventItem(CalendarSource.CONSULTATION, row.requestId(), "Buổi tư vấn",
                subtitle, startsAt, endsAt, startsAt.atZoneSameInstant(zone).toLocalDate(),
                row.status().name(), "nutrimom://consultations/" + row.requestId(), false);
    }

    /** @param at mốc của ĐÚNG lần lặp này, không phải {@code startsAt} (điểm neo) của chuỗi. */
    private static CalendarEventItem toItem(CalendarReminderEntity reminder, OffsetDateTime at,
                                            ZoneId zone, String status, boolean recurring) {
        return new CalendarEventItem(CalendarSource.REMINDER, reminder.getId(), reminder.getTitle(),
                reminder.getFacilityName(), at, null,
                at.atZoneSameInstant(zone).toLocalDate(), status,
                "nutrimom://calendar/reminders/" + reminder.getId(), recurring);
    }

    // ----- Kiểm tra tham số -----

    private void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Cần cả from và to để lấy lịch.");
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "to phải không nhỏ hơn from.");
        }
        if (from.plusDays(properties.getMaxRangeDays()).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khoảng xem lịch tối đa " + properties.getMaxRangeDays() + " ngày.");
        }
    }

    /** {@code YearMonth.of} ném DateTimeException với month ngoài 1-12 — không bắt thì thành 500. */
    private static YearMonth yearMonth(int year, int month) {
        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Năm hoặc tháng không hợp lệ.");
        }
    }
}
