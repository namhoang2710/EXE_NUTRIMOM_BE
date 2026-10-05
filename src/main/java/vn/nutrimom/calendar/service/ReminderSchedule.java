package vn.nutrimom.calendar.service;

import java.time.DateTimeException;
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
     * Trần số chu kỳ phải xét khi tìm lần lặp kế tiếp.
     *
     * <p>Chu kỳ đầu tiên được tính <strong>thẳng</strong> bằng phép chia làm tròn lên, nên nó đã
     * rơi vào đúng hoặc sau ngày địa phương của mốc đang đứng — không phải dò ngày nào cả. Chu kỳ
     * đó chỉ có thể không ra mốc vì hai lý do: mọi giờ trong ngày của nó đã trôi qua, hoặc (WEEKLY)
     * các thứ đã chọn trong tuần neo đều nằm trước ngày neo. Chu kỳ kế tiếp giải quyết cả hai vì
     * mọi ngày của nó đều muộn hơn hẳn, nên 2 lượt là đủ — để 4 cho dư, phòng múi giờ nhảy nguyên
     * một ngày lịch (Pacific/Apia bỏ hẳn ngày 2011-12-30).</p>
     *
     * <p>Khác trần dò theo ngày trước đây ở chỗ trần này <strong>không</strong> phụ thuộc
     * {@code interval}: WEEKLY mỗi 365 tuần cũng chỉ tốn đúng từng ấy lượt. Trần cũ 800 ngày làm
     * WEEKLY từ 115 tuần và MONTHLY từ 27 tháng âm thầm trả về rỗng — lịch vẫn vẽ đủ mốc nhưng
     * thông báo không bao giờ bắn.</p>
     */
    private static final int MAX_PERIOD_PROBES = 4;

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
     *
     * <p>Nhảy thẳng tới chu kỳ cần tìm bằng phép cộng ngày/tuần/tháng chứ không dò từng ngày: chi
     * phí một lời gọi là hằng số, không tăng theo {@code interval}. Job nhắc lịch gọi method này
     * trong vòng lặp đuổi kịp, và màn hình danh sách gọi nó cho từng dòng — dò từng ngày thì cả
     * hai đều trả giá theo {@code interval}.</p>
     */
    public Optional<OffsetDateTime> nextOccurrenceAfter(OffsetDateTime watermark) {
        if (single != null) {
            return watermark == null || single.isAfter(watermark)
                    ? Optional.of(single) : Optional.empty();
        }
        long firstPeriod = watermark == null ? 0L
                : firstPeriodOnOrAfter(watermark.atZoneSameInstant(zone).toLocalDate());
        try {
            for (int probe = 0; probe < MAX_PERIOD_PROBES; probe++) {
                for (LocalDate date : periodDates(firstPeriod + probe)) {
                    // Đúng bộ lọc của occurrencesBetween: hai đường phải công nhận cùng một tập
                    // ngày. Lệch nhau thì người dùng nhận thông báo cho một mốc mà lịch không hiện
                    // và API cũng từ chối đánh dấu đã làm.
                    if (date.isBefore(anchorDate) || !matches(date)) {
                        continue;
                    }
                    if (until != null && date.isAfter(until)) {
                        // Ngày sinh ra theo thứ tự tăng dần nên phía sau không còn gì hợp lệ.
                        return Optional.empty();
                    }
                    for (LocalTime time : timesOfDay) {
                        OffsetDateTime at = instantAt(date, time);
                        if (watermark == null || at.isAfter(watermark)) {
                            return Optional.of(at);
                        }
                    }
                }
            }
        } catch (DateTimeException | ArithmeticException ex) {
            // Chuỗi đã chạy khỏi miền ngày biểu diễn được: coi như hết chuỗi. Để exception lọt ra
            // thì transaction của job nhắc lịch rollback rồi thử lại dòng đó mãi mãi.
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Chỉ số chu kỳ nhỏ nhất mà ngày của nó rơi vào đúng hoặc sau {@code from}.
     *
     * <p>Lấy <em>ngày</em> chứ không phải mốc tuyệt đối làm chuẩn là cố ý: một ngày có thể có nhiều
     * mốc giờ, nên chu kỳ chứa {@code from} vẫn phải được xét lại chứ không được nhảy qua.</p>
     *
     * <p>Trôi qua âm — mốc đang đứng nằm trước mốc neo vì lệch đồng hồ hoặc vì nhắc nhở được hẹn ở
     * tương lai — kẹp về 0 để luôn bắt đầu từ chu kỳ đầu chuỗi.</p>
     */
    private long firstPeriodOnOrAfter(LocalDate from) {
        long elapsed = switch (rule) {
            case DAILY -> ChronoUnit.DAYS.between(anchorDate, from);
            // Hai đầu đều là Thứ Hai nên phép trừ theo tuần chẵn, không bị cắt phần dư.
            case WEEKLY -> ChronoUnit.WEEKS.between(weekStart(anchorDate), weekStart(from));
            case MONTHLY -> ChronoUnit.MONTHS.between(anchorDate.withDayOfMonth(1),
                    from.withDayOfMonth(1));
        };
        if (elapsed <= 0) {
            return 0L;
        }
        // Chia làm tròn lên; elapsed đã chắc chắn dương nên không cần Math.ceilDiv (Java 18+).
        return (elapsed + interval - 1) / interval;
    }

    /**
     * Các ngày lặp <em>thô</em> của chu kỳ thứ {@code period}, tăng dần — chưa lọc theo mốc neo và
     * {@code until}, phần đó người gọi làm cho giống hệt {@code occurrencesBetween}.
     *
     * <p>DAILY và MONTHLY mỗi chu kỳ đúng một ngày; WEEKLY trả về các thứ đã chọn trong tuần của
     * chu kỳ, theo thứ tự Thứ Hai → Chủ Nhật.</p>
     */
    private List<LocalDate> periodDates(long period) {
        long offset = period * interval;
        return switch (rule) {
            case DAILY -> List.of(anchorDate.plusDays(offset));
            case WEEKLY -> weekDates(weekStart(anchorDate).plusWeeks(offset));
            case MONTHLY -> List.of(monthDate(anchorDate.withDayOfMonth(1).plusMonths(offset)));
        };
    }

    /** Các thứ đã chọn trong tuần bắt đầu từ {@code monday}, theo thứ tự trong tuần. */
    private List<LocalDate> weekDates(LocalDate monday) {
        List<LocalDate> dates = new ArrayList<>(daysOfWeek.size());
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            if (daysOfWeek.contains(date.getDayOfWeek())) {
                dates.add(date);
            }
        }
        return dates;
    }

    /**
     * Ngày lặp trong {@code month} (truyền vào là ngày 1 của tháng).
     *
     * <p>Tháng ngắn hơn ngày neo (31 → tháng 2) thì lùi về ngày cuối tháng. Số thứ tự tháng vẫn
     * đếm từ mốc neo chứ không cộng dồn từ lần trước, nên "ngày 31 hằng tháng" sau 28/2 quay lại
     * đúng 31/3 thay vì trôi dần về cuối tháng.</p>
     */
    private LocalDate monthDate(LocalDate month) {
        return month.withDayOfMonth(Math.min(anchorDate.getDayOfMonth(), month.lengthOfMonth()));
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
