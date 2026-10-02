package vn.nutrimom.calendar.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.domain.RepeatRule;

/**
 * Quy tắc lặp của một nhắc nhở, đọc ra từ entity.
 *
 * <p>Các lần lặp được <strong>tính ra</strong> mỗi lần cần tới, không lưu sẵn thành dòng. Lưu sẵn
 * thì đổi giờ một cái phải sửa toàn bộ chuỗi, và chuỗi vô hạn thì không dựng trước được.</p>
 *
 * <p>Giờ trong ngày được giữ theo <em>giờ địa phương</em> của dòng ({@code timezone}), không phải
 * một khoảng cách cố định tính bằng giây: "20:00 mỗi ngày" phải vẫn là 20:00 sau khi đổi giờ mùa,
 * chứ không trôi thành 19:00.</p>
 */
public final class ReminderSchedule {

    /**
     * Trần số ngày dò tới khi tìm lần lặp kế tiếp.
     *
     * <p>Đủ cho mọi quy tắc hợp lệ (xa nhất là MONTHLY mỗi 12 tháng ≈ 366 ngày), và là chốt chặn để
     * một quy tắc hỏng không biến vòng lặp thành vô hạn.</p>
     */
    private static final int LOOKAHEAD_DAYS = 800;

    private final ZoneId zone;
    private final LocalDate anchorDate;
    private final List<LocalTime> timesOfDay;
    private final RepeatRule rule;
    private final int interval;
    private final Set<DayOfWeek> daysOfWeek;
    private final LocalDate until;
    /** Mốc duy nhất khi không lặp; null khi đang lặp. */
    private final OffsetDateTime single;

    private ReminderSchedule(ZoneId zone, LocalDate anchorDate, List<LocalTime> timesOfDay,
                             RepeatRule rule, int interval, Set<DayOfWeek> daysOfWeek,
                             LocalDate until, OffsetDateTime single) {
        this.zone = zone;
        this.anchorDate = anchorDate;
        this.timesOfDay = timesOfDay;
        this.rule = rule;
        this.interval = interval;
        this.daysOfWeek = daysOfWeek;
        this.until = until;
        this.single = single;
    }

    public static ReminderSchedule of(CalendarReminderEntity reminder) {
        ZoneId zone = zoneOf(reminder);
        if (reminder.getRepeatRule() == null) {
            return new ReminderSchedule(zone, null, List.of(), null, 0, Set.of(), null,
                    reminder.getStartsAt());
        }
        LocalDate anchorDate = reminder.getStartsAt().atZoneSameInstant(zone).toLocalDate();
        List<LocalTime> times = parseTimes(reminder.getRepeatTimesOfDay());
        if (times.isEmpty()) {
            times = List.of(reminder.getStartsAt().atZoneSameInstant(zone).toLocalTime());
        }
        Set<DayOfWeek> days = parseDays(reminder.getRepeatDaysOfWeek());
        if (reminder.getRepeatRule() == RepeatRule.WEEKLY && days.isEmpty()) {
            days = EnumSet.of(anchorDate.getDayOfWeek());
        }
        int interval = reminder.getRepeatInterval() == null ? 1 : reminder.getRepeatInterval();
        return new ReminderSchedule(zone, anchorDate, times, reminder.getRepeatRule(),
                Math.max(interval, 1), days, reminder.getRepeatUntil(), null);
    }

    public boolean repeats() {
        return single == null;
    }

    /**
     * Mọi lần lặp rơi vào cửa sổ nửa mở {@code [from, to)}, tăng dần.
     *
     * <p>Cửa sổ truy vấn lịch bị chặn trần nên số lần lặp trả về luôn hữu hạn; phần chặn tổng số
     * mốc của cả response nằm ở {@code CalendarQueryService}.</p>
     */
    public List<OffsetDateTime> occurrencesBetween(OffsetDateTime from, OffsetDateTime to) {
        if (single != null) {
            return within(single, from, to) ? List.of(single) : List.of();
        }
        LocalDate first = maxDate(anchorDate, from.atZoneSameInstant(zone).toLocalDate());
        LocalDate last = to.atZoneSameInstant(zone).toLocalDate();
        if (until != null && until.isBefore(last)) {
            last = until;
        }
        List<OffsetDateTime> result = new ArrayList<>();
        // Lùi một ngày để không bỏ sót mốc khuya của ngày trước vẫn rơi vào cửa sổ ở múi giờ khác.
        for (LocalDate date = first.minusDays(1); !date.isAfter(last); date = date.plusDays(1)) {
            if (date.isBefore(anchorDate) || !matches(date)) {
                continue;
            }
            for (LocalTime time : timesOfDay) {
                OffsetDateTime at = instantAt(date, time);
                if (within(at, from, to)) {
                    result.add(at);
                }
            }
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    /**
     * Lần lặp đầu tiên sau {@code watermark} (loại trừ), hoặc rỗng nếu chuỗi đã hết.
     *
     * <p>{@code watermark} null nghĩa là chưa bắn lần nào — khi đó lần lặp đầu tiên của chuỗi cũng
     * được tính, kể cả nếu nó rơi đúng vào mốc bắt đầu.</p>
     */
    public Optional<OffsetDateTime> nextOccurrenceAfter(OffsetDateTime watermark) {
        if (single != null) {
            return watermark == null || single.isAfter(watermark) ? Optional.of(single) : Optional.empty();
        }
        LocalDate start = watermark == null ? anchorDate
                : maxDate(anchorDate, watermark.atZoneSameInstant(zone).toLocalDate());
        for (int step = 0; step <= LOOKAHEAD_DAYS; step++) {
            LocalDate date = start.plusDays(step);
            if (until != null && date.isAfter(until)) {
                return Optional.empty();
            }
            if (date.isBefore(anchorDate) || !matches(date)) {
                continue;
            }
            for (LocalTime time : timesOfDay) {
                OffsetDateTime at = instantAt(date, time);
                if (watermark == null || at.isAfter(watermark)) {
                    return Optional.of(at);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code true} nếu {@code at} đúng là một lần lặp của chuỗi — dùng khi người dùng đánh dấu một ngày. */
    public boolean isOccurrence(OffsetDateTime at) {
        return occurrencesBetween(at, at.plusNanos(1_000_000)).stream()
                .anyMatch(candidate -> candidate.isEqual(at));
    }

    /**
     * Mốc của một lần lặp, luôn chuẩn hoá về UTC.
     *
     * <p>Giờ được dựng theo múi giờ của nhắc nhở (nên "20:00" vẫn là 20:00 sau khi đổi giờ mùa),
     * nhưng đưa ra ngoài thì phải cùng một dạng với mọi mốc khác của API — trộn {@code +07:00} với
     * {@code Z} trong cùng một response là bắt FE tự đoán.</p>
     */
    private OffsetDateTime instantAt(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(zone).toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private boolean matches(LocalDate date) {
        return switch (rule) {
            case DAILY -> ChronoUnit.DAYS.between(anchorDate, date) % interval == 0;
            case WEEKLY -> daysOfWeek.contains(date.getDayOfWeek())
                    && ChronoUnit.WEEKS.between(weekStart(anchorDate), weekStart(date)) % interval == 0;
            // Tháng ngắn hơn ngày neo (31 → tháng 2) thì lùi về ngày cuối tháng, không nhảy sang
            // tháng sau — mốc "ngày 31 hằng tháng" vẫn phải xuất hiện đúng một lần mỗi tháng.
            case MONTHLY -> date.getDayOfMonth()
                    == Math.min(anchorDate.getDayOfMonth(), date.lengthOfMonth())
                    && ChronoUnit.MONTHS.between(anchorDate.withDayOfMonth(1),
                            date.withDayOfMonth(1)) % interval == 0;
        };
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private static boolean within(OffsetDateTime at, OffsetDateTime from, OffsetDateTime to) {
        return !at.isBefore(from) && at.isBefore(to);
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /**
     * Múi giờ đã được kiểm lúc ghi; một dòng cũ hỏng không được phép làm đổ cả màn hình lịch nên
     * rơi về giờ VN.
     */
    private static ZoneId zoneOf(CalendarReminderEntity reminder) {
        try {
            return ZoneId.of(reminder.getTimezone());
        } catch (RuntimeException ex) {
            return CalendarZone.VIETNAM_ZONE;
        }
    }

    static List<LocalTime> parseTimes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<LocalTime> times = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            times.add(LocalTime.parse(part.trim()));
        }
        return times.stream().sorted().toList();
    }

    static Set<DayOfWeek> parseDays(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : csv.split(",")) {
            days.add(DayOfWeek.valueOf(part.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        return days;
    }
}
