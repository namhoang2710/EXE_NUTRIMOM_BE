package vn.nutrimom.calendar.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.domain.CalendarReminderOccurrenceEntity;
import vn.nutrimom.calendar.domain.ReminderStatus;
import vn.nutrimom.calendar.domain.ReminderType;
import vn.nutrimom.calendar.domain.RepeatRule;
import vn.nutrimom.calendar.dto.CalendarDtos.CreateReminderFromRecordRequest;
import vn.nutrimom.calendar.dto.CalendarDtos.CreateReminderRequest;
import vn.nutrimom.calendar.dto.CalendarDtos.ReminderResponse;
import vn.nutrimom.calendar.dto.CalendarDtos.RepeatSpec;
import vn.nutrimom.calendar.dto.CalendarDtos.UpsertOccurrenceRequest;
import vn.nutrimom.calendar.dto.CalendarDtos.UpdateReminderRequest;
import vn.nutrimom.calendar.repository.CalendarReminderOccurrenceRepository;
import vn.nutrimom.calendar.repository.CalendarReminderRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.medicalrecord.domain.MedicalRecordEntity;
import vn.nutrimom.medicalrecord.repository.MedicalRecordRepository;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

/**
 * Nhắc nhở do người dùng tự tạo — nguồn ghi duy nhất của module lịch (spec mục 10).
 *
 * <p>Mọi lượt đọc một dòng đi qua repository đã có {@code owner_user_id} trong WHERE rồi mới qua
 * {@link AccessGuard}, nên truy cập chéo trả 404 chứ không 403: không để lộ rằng id đó có tồn tại.</p>
 */
@Service
public class CalendarReminderService {

    private static final String NOT_FOUND_MESSAGE = "Không tìm thấy nhắc nhở.";
    private static final String RECORD_NOT_FOUND_MESSAGE = "Không tìm thấy hồ sơ y tế.";

    private final CalendarReminderRepository reminders;
    private final CalendarReminderOccurrenceRepository occurrences;
    private final MedicalRecordRepository medicalRecords;
    private final PregnancyRepository pregnancies;
    private final AccessGuard guard;
    private final CalendarZone zones;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarReminderService(CalendarReminderRepository reminders,
                                   CalendarReminderOccurrenceRepository occurrences,
                                   MedicalRecordRepository medicalRecords,
                                   PregnancyRepository pregnancies,
                                   AccessGuard guard,
                                   CalendarZone zones,
                                   CalendarProperties properties,
                                   Clock clock) {
        this.reminders = reminders;
        this.occurrences = occurrences;
        this.medicalRecords = medicalRecords;
        this.pregnancies = pregnancies;
        this.guard = guard;
        this.zones = zones;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param from   null = hôm nay theo múi giờ người dùng.
     * @param to     null = {@code from} cộng cửa sổ mặc định.
     * @param status null = mọi trạng thái, kể cả {@link ReminderStatus#CANCELLED} (danh sách này là
     *               màn hình quản lý, khác với lịch vốn giấu mốc đã huỷ).
     */
    @Transactional(readOnly = true)
    public List<ReminderResponse> list(String userId, LocalDate from, LocalDate to,
                                       ReminderStatus status, String timezone) {
        ZoneId zone = zones.resolve(timezone, userId);
        LocalDate start = from != null ? from : LocalDate.ofInstant(clock.instant(), zone);
        LocalDate end = to != null ? to : start.plusDays(properties.getDashboardLookaheadDays());
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "to phải không nhỏ hơn from.");
        }
        if (start.plusDays(properties.getMaxRangeDays()).isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khoảng xem lịch tối đa " + properties.getMaxRangeDays() + " ngày.");
        }
        return reminders.findForList(userId, status,
                        start.atStartOfDay(zone).toOffsetDateTime(),
                        end.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), start)
                .stream().map(reminder -> toResponse(reminder, zone, null)).toList();
    }

    @Transactional(readOnly = true)
    public ReminderResponse get(String userId, String id) {
        CalendarReminderEntity reminder = loadOwned(userId, id);
        return toResponse(reminder, zones.resolve(reminder.getTimezone(), userId), null);
    }

    @Transactional
    public ReminderResponse create(String userId, CreateReminderRequest request) {
        ZoneId zone = zones.resolve(request.timezone(), userId);
        CalendarReminderEntity reminder = new CalendarReminderEntity();
        reminder.setOwnerUserId(userId);
        reminder.setType(request.type());
        reminder.setTitle(request.title().trim());
        reminder.setTimezone(zone.getId());
        reminder.setNote(trimToNull(request.note()));
        reminder.setFacilityName(trimToNull(request.facilityName()));
        if (request.pregnancyId() != null && !request.pregnancyId().isBlank()) {
            guard.requireOwned(pregnancies.findByIdAndOwnerUserId(request.pregnancyId(), userId),
                    "Không tìm thấy thai kỳ.");
            reminder.setPregnancyId(request.pregnancyId());
        }
        applySchedule(reminder, request.startsAt(), request.remindMinutesBefore());
        applyRepeat(reminder, request.repeat(), null);
        refreshRemindAt(reminder);
        reminders.saveAndFlush(reminder);
        return toResponse(reminder, zone, null);
    }

    /**
     * Nút "thêm vào lịch nhắc nhở" trên một hồ sơ y tế.
     *
     * <p>Tiêu đề, cơ sở y tế và thai kỳ được <strong>copy</strong> chứ không tham chiếu ngược: hồ sơ
     * có thể bị xoá mềm sau này mà mốc nhắc vẫn phải hiện đúng trên lịch.</p>
     */
    @Transactional
    public ReminderResponse createFromMedicalRecord(String userId, String recordId,
                                                    CreateReminderFromRecordRequest request) {
        MedicalRecordEntity record = guard.requireOwned(
                medicalRecords.findByIdAndOwnerUserIdAndDeletedAtIsNull(recordId, userId),
                RECORD_NOT_FOUND_MESSAGE);
        ZoneId zone = zones.resolve(request.timezone(), userId);
        CalendarReminderEntity reminder = new CalendarReminderEntity();
        reminder.setOwnerUserId(userId);
        reminder.setType(request.type());
        reminder.setTitle(trimToNull(request.title()) != null
                ? request.title().trim() : defaultTitle(request.type(), record));
        reminder.setTimezone(zone.getId());
        reminder.setNote(trimToNull(request.note()));
        reminder.setFacilityName(record.getFacilityName());
        reminder.setPregnancyId(record.getPregnancyId());
        reminder.setSourceRecordId(record.getId());
        applySchedule(reminder, request.startsAt(), request.remindMinutesBefore());
        applyRepeat(reminder, request.repeat(), null);
        refreshRemindAt(reminder);
        reminders.saveAndFlush(reminder);
        return toResponse(reminder, zone, null);
    }

    /**
     * PATCH kiểu null-là-không-đổi, kèm kiểm tra {@code version} tường minh (spec mục 22).
     *
     * <p>Phép so sánh này <em>không</em> thừa so với handler
     * {@code ObjectOptimisticLockingFailureException → VERSION_CONFLICT}: Hibernate dùng version
     * của entity vừa load, không phải version client gửi, nên thiếu nó thì một PATCH mang version
     * cũ sẽ âm thầm ghi đè thay đổi của người khác.</p>
     */
    @Transactional
    public ReminderResponse update(String userId, String id, UpdateReminderRequest request) {
        CalendarReminderEntity reminder = loadOwned(userId, id);
        if (request.version() != reminder.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        ZoneId zone = request.timezone() != null && !request.timezone().isBlank()
                ? zones.resolve(request.timezone(), userId)
                : ZoneId.of(reminder.getTimezone());
        if (request.timezone() != null && !request.timezone().isBlank()) {
            reminder.setTimezone(zone.getId());
        }
        if (request.type() != null) reminder.setType(request.type());
        if (request.title() != null) reminder.setTitle(requireTitle(request.title()));
        if (request.note() != null) reminder.setNote(trimToNull(request.note()));
        if (request.facilityName() != null) reminder.setFacilityName(trimToNull(request.facilityName()));

        // Đổi lịch trước, rồi mới đổi trạng thái: gợi ý mốc kế tiếp phải tính từ mốc MỚI nếu lần
        // PATCH này vừa dời giờ vừa đánh dấu đã khám.
        applySchedule(reminder, request.startsAt(), resolveOffset(reminder, request));
        applyRepeat(reminder, request.repeat(), request.clearRepeat());
        refreshRemindAt(reminder);
        OffsetDateTime nextSuggestion = applyStatus(reminder, request.status());
        reminders.saveAndFlush(reminder);
        return toResponse(reminder, zone, nextSuggestion);
    }

    /** Xoá mềm, giống hồ sơ y tế: giữ dòng để thống kê và để khoá ngoại nguồn không bị treo. */
    @Transactional
    public void delete(String userId, String id) {
        CalendarReminderEntity reminder = loadOwned(userId, id);
        reminder.setDeletedAt(OffsetDateTime.now(clock));
        reminders.saveAndFlush(reminder);
    }

    // ----- Helpers -----

    /**
     * Ghi {@code startsAt} + {@code remindMinutesBefore} và tính lại cột dẫn xuất {@code remindAt}.
     *
     * <p>Mọi đường ghi phải đi qua đây, nếu không hai cột sẽ lệch nhau và vi phạm
     * {@code ck_calendar_reminders_remind_at} — một ràng buộc mà test (chạy H2
     * {@code create-drop}, không có CHECK) sẽ không bắt được.</p>
     *
     * <p>Với chuỗi lặp, {@code remindAt} là mốc nhắc của <em>lần lặp kế tiếp chưa bắn</em>, không
     * phải của {@code startsAt}: {@code startsAt} chỉ còn là điểm neo của chuỗi.</p>
     */
    private void applySchedule(CalendarReminderEntity reminder, OffsetDateTime startsAt,
                               Integer remindMinutesBefore) {
        if (startsAt != null) {
            requireFuture(startsAt);
            reminder.setStartsAt(startsAt.withOffsetSameInstant(ZoneOffset.UTC));
        }
        if (remindMinutesBefore != null) {
            requireOffsetInRange(remindMinutesBefore);
        }
        reminder.setRemindMinutesBefore(remindMinutesBefore);
        refreshRemindAt(reminder);
    }

    /** Dời {@code remindAt} tới lần lặp kế tiếp chưa bắn; null khi chuỗi đã hết hoặc không nhắc. */
    private static void refreshRemindAt(CalendarReminderEntity reminder) {
        Integer before = reminder.getRemindMinutesBefore();
        if (before == null) {
            reminder.setRemindAt(null);
            return;
        }
        reminder.setRemindAt(ReminderSchedule.of(reminder)
                .nextOccurrenceAfter(reminder.getLastFiredOccurrence())
                .map(occurrence -> occurrence.minusMinutes(before))
                .orElse(null));
    }

    /**
     * Ghi quy tắc lặp. {@code spec} null = không đổi; {@code clear} = bỏ lặp, về mốc một lần.
     *
     * <p>Sửa quy tắc là sửa <strong>cả chuỗi</strong> (scope {@code ALL} của spec mục 10). Muốn
     * "từ mai về sau đổi giờ" thì đặt {@code repeat.until} = hôm nay cho chuỗi cũ rồi tạo chuỗi
     * mới — hai lời gọi API, người dùng không thấy khác biệt, còn phần {@code THIS_AND_FUTURE}
     * thật thì đắt hơn toàn bộ phần còn lại cộng lại.</p>
     */
    private void applyRepeat(CalendarReminderEntity reminder, RepeatSpec spec, Boolean clear) {
        if (Boolean.TRUE.equals(clear)) {
            reminder.setRepeatRule(null);
            reminder.setRepeatInterval(null);
            reminder.setRepeatDaysOfWeek(null);
            reminder.setRepeatTimesOfDay(null);
            reminder.setRepeatUntil(null);
            return;
        }
        if (spec == null) {
            return;
        }
        int interval = spec.interval() == null ? 1 : spec.interval();
        if (interval < 1 || interval > 365) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Khoảng lặp phải từ 1 đến 365.");
        }
        if (spec.rule() != RepeatRule.WEEKLY
                && spec.daysOfWeek() != null && !spec.daysOfWeek().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ kiểu lặp theo tuần mới chọn được thứ trong tuần.");
        }
        List<LocalTime> times = spec.timesOfDay() == null ? List.of()
                : spec.timesOfDay().stream().distinct().sorted().toList();
        if (times.size() > properties.getMaxTimesPerDay()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Tối đa " + properties.getMaxTimesPerDay() + " mốc giờ trong một ngày.");
        }
        if (spec.until() != null
                && spec.until().isBefore(reminder.getStartsAt()
                        .atZoneSameInstant(ZoneId.of(reminder.getTimezone())).toLocalDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Ngày kết thúc lặp phải sau ngày bắt đầu.");
        }
        reminder.setRepeatRule(spec.rule());
        reminder.setRepeatInterval(interval);
        reminder.setRepeatDaysOfWeek(spec.rule() == RepeatRule.WEEKLY
                ? joinDays(spec.daysOfWeek()) : null);
        reminder.setRepeatTimesOfDay(times.isEmpty() ? null : joinTimes(times));
        reminder.setRepeatUntil(spec.until());
    }

    private static String joinDays(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        return days.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    private static String joinTimes(List<LocalTime> times) {
        return times.stream().map(LocalTime::toString).collect(Collectors.joining(","));
    }

    private static RepeatSpec repeatOf(CalendarReminderEntity reminder) {
        if (reminder.getRepeatRule() == null) {
            return null;
        }
        Set<DayOfWeek> days = ReminderSchedule.parseDays(reminder.getRepeatDaysOfWeek());
        List<LocalTime> times = ReminderSchedule.parseTimes(reminder.getRepeatTimesOfDay());
        return new RepeatSpec(reminder.getRepeatRule(), reminder.getRepeatInterval(),
                days.isEmpty() ? null : days, times.isEmpty() ? null : times,
                reminder.getRepeatUntil());
    }

    /**
     * Trả về gợi ý mốc kế tiếp nếu lần PATCH này chuyển sang DONE; ngược lại null.
     *
     * <p>Chuỗi lặp không có khái niệm "cả chuỗi đã xong": đánh dấu từng lần bằng
     * {@link #upsertOccurrence}, còn muốn dừng hẳn thì {@code CANCELLED} hoặc đặt
     * {@code repeat.until}.</p>
     */
    private OffsetDateTime applyStatus(CalendarReminderEntity reminder, ReminderStatus status) {
        if (status == null || status == reminder.getStatus()) {
            return null;
        }
        if (status == ReminderStatus.DONE && reminder.getRepeatRule() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Nhắc nhở lặp lại thì đánh dấu từng lần, không đánh dấu cả chuỗi. "
                            + "Muốn dừng hẳn thì huỷ hoặc đặt ngày kết thúc lặp.");
        }
        reminder.setStatus(status);
        if (status != ReminderStatus.DONE || reminder.getType().nextSuggestionInterval() == null) {
            return null;
        }
        return reminder.getStartsAt().plus(reminder.getType().nextSuggestionInterval());
    }

    /**
     * Đánh dấu MỘT lần lặp: đã uống / bỏ qua, hoặc bỏ đánh dấu ({@code status} null).
     *
     * <p>Chỉ ghi ngoại lệ, không ghi mọi lần lặp — xem javadoc của
     * {@code CalendarReminderOccurrenceEntity}. {@code occurrenceAt} phải trùng khít một mốc tính
     * ra từ quy tắc lặp, nếu không thì bảng ngoại lệ sẽ đầy những dòng mồ côi không bao giờ khớp
     * với mốc nào trên lịch.</p>
     */
    @Transactional
    public ReminderResponse upsertOccurrence(String userId, String id,
                                             UpsertOccurrenceRequest request) {
        CalendarReminderEntity reminder = loadOwned(userId, id);
        OffsetDateTime at = request.occurrenceAt().withOffsetSameInstant(ZoneOffset.UTC);
        if (!ReminderSchedule.of(reminder).isOccurrence(at)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Mốc này không thuộc lịch lặp của nhắc nhở.");
        }
        CalendarReminderOccurrenceEntity existing =
                occurrences.findByReminderIdAndOccurrenceAt(id, at).orElse(null);
        if (request.status() == null) {
            if (existing != null) {
                occurrences.delete(existing);
                occurrences.flush();
            }
        } else if (existing == null) {
            CalendarReminderOccurrenceEntity entity = new CalendarReminderOccurrenceEntity();
            entity.setReminderId(id);
            entity.setOccurrenceAt(at);
            entity.setStatus(request.status());
            occurrences.saveAndFlush(entity);
        } else {
            existing.setStatus(request.status());
            occurrences.saveAndFlush(existing);
        }
        return toResponse(reminder, ZoneId.of(reminder.getTimezone()), null);
    }

    /**
     * null nghĩa là không đổi, nên muốn bỏ hẳn việc nhắc phải nói ra bằng
     * {@code clear_remind_minutes_before}.
     */
    private static Integer resolveOffset(CalendarReminderEntity reminder,
                                         UpdateReminderRequest request) {
        if (Boolean.TRUE.equals(request.clearRemindMinutesBefore())) {
            return null;
        }
        return request.remindMinutesBefore() != null
                ? request.remindMinutesBefore() : reminder.getRemindMinutesBefore();
    }

    private CalendarReminderEntity loadOwned(String userId, String id) {
        return guard.requireOwned(
                reminders.findByIdAndOwnerUserIdAndDeletedAtIsNull(id, userId), NOT_FOUND_MESSAGE);
    }

    private void requireFuture(OffsetDateTime startsAt) {
        if (!startsAt.isAfter(OffsetDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Thời điểm nhắc nhở phải ở tương lai.");
        }
    }

    private void requireOffsetInRange(int minutes) {
        if (minutes < 0 || minutes > properties.getMaxRemindMinutesBefore()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ nhắc trước tối đa " + properties.getMaxRemindMinutesBefore() + " phút.");
        }
    }

    private static String requireTitle(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Tiêu đề không được để trống.");
        }
        return trimmed;
    }

    private static String defaultTitle(ReminderType type, MedicalRecordEntity record) {
        String prefix = type == ReminderType.ROUTINE_CHECKUP ? "Khám định kỳ" : "Tái khám";
        return truncate(prefix + ": " + record.getTitle(), 255);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    ReminderResponse toResponse(CalendarReminderEntity reminder, ZoneId zone,
                                OffsetDateTime nextSuggestion) {
        // Lần lặp sắp tới tính từ "bây giờ" chứ không từ mốc đã bắn: đây là thứ FE hiện lên màn
        // hình ("lần tới: 20:00 hôm nay"), không phải trạng thái nội bộ của job.
        OffsetDateTime nextOccurrence = ReminderSchedule.of(reminder)
                .nextOccurrenceAfter(OffsetDateTime.now(clock)).orElse(null);
        return new ReminderResponse(reminder.getId(), reminder.getType(), reminder.getTitle(),
                reminder.getStartsAt(), reminder.getTimezone(),
                reminder.getStartsAt().atZoneSameInstant(zone).toLocalDate(),
                reminder.getNote(), reminder.getFacilityName(), reminder.getRemindMinutesBefore(),
                reminder.getRemindAt(), reminder.getStatus(), reminder.getNotifiedAt(),
                reminder.getPregnancyId(), reminder.getSourceRecordId(), repeatOf(reminder),
                nextOccurrence, nextSuggestion,
                reminder.getVersion(), reminder.getCreatedAt(), reminder.getUpdatedAt());
    }
}
