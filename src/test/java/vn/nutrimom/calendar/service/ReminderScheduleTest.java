package vn.nutrimom.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.domain.ReminderType;
import vn.nutrimom.calendar.domain.RepeatRule;

/**
 * Khai triển quy tắc lặp.
 *
 * <p>Trọng tâm là {@code nextOccurrenceAfter} — đường duy nhất dẫn tới việc gửi thông báo. Nó từng
 * dò từng ngày tới trần 800 ngày, nên WEEKLY từ 115 tuần và MONTHLY từ 27 tháng âm thầm trả về
 * rỗng: lịch vẫn vẽ đủ mốc, {@code remind_at} thì null vĩnh viễn và nhắc nhở không bao giờ kêu.
 * Validation lại cho {@code interval} tới 365 cho cả ba rule, nên khoảng vỡ đó hoàn toàn hợp lệ về
 * mặt API.</p>
 */
class ReminderScheduleTest {

    private static final String VN = "Asia/Ho_Chi_Minh";
    /** Có đổi giờ mùa: 2026-03-29 nhảy 02:00 → 03:00, 2026-10-25 lùi 03:00 → 02:00. */
    private static final String PARIS = "Europe/Paris";

    // ---------------------------------------------------------------- ngữ nghĩa mốc đang đứng

    @Test
    void returnsTheFirstOccurrenceWhenNothingHasFired() {
        ReminderSchedule schedule = daily("2026-01-01T02:00:00Z", 1);

        assertThat(schedule.nextOccurrenceAfter(null))
                .contains(OffsetDateTime.parse("2026-01-01T02:00:00Z"));
    }

    /**
     * Mốc giờ sớm nhất trong ngày thắng, kể cả khi nó nằm trước giờ của {@code starts_at} — hành vi
     * sẵn có của {@code occurrencesBetween}, hai đường phải giống nhau.
     */
    @Test
    void returnsTheEarliestTimeOfDayOnTheAnchorDay() {
        ReminderSchedule schedule = schedule("2026-01-01T13:00:00Z", VN, RepeatRule.DAILY, 1,
                null, "08:00,20:00", null);

        assertThat(schedule.nextOccurrenceAfter(null))
                .contains(OffsetDateTime.parse("2026-01-01T01:00:00Z"));
    }

    /** Thuốc sáng/tối là một nhắc nhở: xong cữ sáng thì mốc kế tiếp là cữ tối, không phải mai. */
    @Test
    void advancesToTheNextTimeOfDayWithinTheSameDay() {
        ReminderSchedule schedule = schedule("2026-01-01T13:00:00Z", VN, RepeatRule.DAILY, 1,
                null, "08:00,20:00", null);

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-01T01:00:00Z")))
                .contains(OffsetDateTime.parse("2026-01-01T13:00:00Z"));
    }

    @Test
    void skipsAnOccurrenceEqualToTheWatermark() {
        ReminderSchedule schedule = schedule("2026-01-01T13:00:00Z", VN, RepeatRule.DAILY, 1,
                null, "08:00,20:00", null);

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-01T13:00:00Z")))
                .contains(OffsetDateTime.parse("2026-01-02T01:00:00Z"));
    }

    /** Nhắc nhở hẹn ở tương lai (hoặc đồng hồ lệch): vẫn phải ra mốc đầu chuỗi, không ra rỗng. */
    @Test
    void returnsTheFirstOccurrenceWhenTheWatermarkPrecedesTheAnchor() {
        ReminderSchedule schedule = daily("2026-01-01T02:00:00Z", 1);

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2025-12-22T02:00:00Z")))
                .contains(OffsetDateTime.parse("2026-01-01T02:00:00Z"));
    }

    @Test
    void returnsAOneOffExactlyOnce() {
        ReminderSchedule schedule = schedule("2026-01-01T02:00:00Z", VN, null, null,
                null, null, null);
        OffsetDateTime single = OffsetDateTime.parse("2026-01-01T02:00:00Z");

        assertThat(schedule.nextOccurrenceAfter(null)).contains(single);
        assertThat(schedule.nextOccurrenceAfter(single)).isEmpty();
    }

    // ---------------------------------------------------------------------------------- DAILY

    @Test
    void dailyWithInterval365JumpsExactlyOneYear() {
        assertThat(walk(daily("2026-01-01T02:00:00Z", 365), 2)).containsExactly(
                OffsetDateTime.parse("2026-01-01T02:00:00Z"),
                OffsetDateTime.parse("2027-01-01T02:00:00Z"));
    }

    @Test
    void dailyWithInterval90WalksQuarterByQuarter() {
        assertThat(walk(daily("2026-01-01T02:00:00Z", 90), 4)).containsExactly(
                OffsetDateTime.parse("2026-01-01T02:00:00Z"),
                OffsetDateTime.parse("2026-04-01T02:00:00Z"),
                OffsetDateTime.parse("2026-06-30T02:00:00Z"),
                OffsetDateTime.parse("2026-09-28T02:00:00Z"));
    }

    // --------------------------------------------------------------------------------- WEEKLY

    /**
     * Mốc neo là Thứ Tư nhưng chuỗi chọn cả Thứ Hai: Thứ Hai của <em>tuần neo</em> nằm trước mốc
     * bắt đầu nên không được phát — nhắc nhở không được kêu trước ngày người dùng đặt.
     */
    @Test
    void weeklyEmitsOnlySelectedDaysAndSkipsThePreAnchorDay() {
        // 2026-01-07 là Thứ Tư; Thứ Hai cùng tuần là 2026-01-05.
        ReminderSchedule schedule = weekly("2026-01-07T02:00:00Z", 1, "MONDAY,WEDNESDAY,FRIDAY");

        assertThat(walk(schedule, 4)).containsExactly(
                OffsetDateTime.parse("2026-01-07T02:00:00Z"),
                OffsetDateTime.parse("2026-01-09T02:00:00Z"),
                OffsetDateTime.parse("2026-01-12T02:00:00Z"),
                OffsetDateTime.parse("2026-01-14T02:00:00Z"));
    }

    /** Hồi quy cho đúng con số FE báo: 115 tuần = 805 ngày, vừa vượt trần dò cũ 800 ngày. */
    @Test
    void weeklyWithInterval115ReturnsTheNextOccurrence() {
        ReminderSchedule schedule = weekly("2026-01-07T02:00:00Z", 115, "WEDNESDAY");

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-07T02:00:00Z")))
                .contains(OffsetDateTime.parse("2028-03-22T02:00:00Z"));
    }

    @Test
    void weeklyWithInterval200ReturnsTheNextOccurrence() {
        assertWeeklyJumpsWholeIntervals(200);
    }

    /** Trần validation hiện tại; tính trực tiếp rồi thì nó cũng chỉ là một con số như mọi số khác. */
    @Test
    void weeklyWithInterval365ReturnsTheNextOccurrence() {
        assertWeeklyJumpsWholeIntervals(365);
    }

    @Test
    void weeklyWithoutExplicitDaysUsesTheAnchorWeekday() {
        assertThat(walk(weekly("2026-01-07T02:00:00Z", 3, null), 3)).containsExactly(
                OffsetDateTime.parse("2026-01-07T02:00:00Z"),
                OffsetDateTime.parse("2026-01-28T02:00:00Z"),
                OffsetDateTime.parse("2026-02-18T02:00:00Z"));
    }

    // -------------------------------------------------------------------------------- MONTHLY

    /**
     * Hồi quy cho chỗ báo cáo bỏ sót: 27 tháng ≈ 821 ngày cũng vượt trần cũ, mà 27 tháng dễ chạm
     * hơn 115 tuần nhiều.
     */
    @Test
    void monthlyWithInterval27ReturnsTheNextOccurrence() {
        ReminderSchedule schedule = monthly("2026-01-31T02:00:00Z", 27);

        // Tháng 4 chỉ có 30 ngày nên mốc neo ngày 31 lùi về 30.
        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-31T02:00:00Z")))
                .contains(OffsetDateTime.parse("2028-04-30T02:00:00Z"));
    }

    @Test
    void monthlyWithInterval120ReturnsTheNextOccurrence() {
        ReminderSchedule schedule = monthly("2026-01-31T02:00:00Z", 120);

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-31T02:00:00Z")))
                .contains(OffsetDateTime.parse("2036-01-31T02:00:00Z"));
    }

    /** Lùi về ngày cuối tháng nhưng không trôi: sau 28/2 phải quay lại đúng 31/3. */
    @Test
    void monthlyClampsShortMonthsWithoutDrifting() {
        assertThat(walk(monthly("2026-01-31T02:00:00Z", 1), 5)).containsExactly(
                OffsetDateTime.parse("2026-01-31T02:00:00Z"),
                OffsetDateTime.parse("2026-02-28T02:00:00Z"),
                OffsetDateTime.parse("2026-03-31T02:00:00Z"),
                OffsetDateTime.parse("2026-04-30T02:00:00Z"),
                OffsetDateTime.parse("2026-05-31T02:00:00Z"));
    }

    @Test
    void monthlyClampsToFebruaryTwentyNineInALeapYear() {
        ReminderSchedule schedule = monthly("2028-01-31T02:00:00Z", 1);

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2028-01-31T02:00:00Z")))
                .contains(OffsetDateTime.parse("2028-02-29T02:00:00Z"));
    }

    /**
     * Mốc đang đứng ở giữa tháng, sau mốc lặp của chính tháng đó: chu kỳ tính thẳng ra rơi vào
     * 05/02 — đã trôi qua — nên phải xét tiếp chu kỳ sau. Ca này chứng minh một lượt là không đủ.
     */
    @Test
    void monthlyResumesFromAWatermarkPastThisMonthsOccurrence() {
        ReminderSchedule schedule = monthly("2026-01-05T02:00:00Z", 1);

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-02-20T02:00:00Z")))
                .contains(OffsetDateTime.parse("2026-03-05T02:00:00Z"));
    }

    // ---------------------------------------------------------------------------------- until

    @Test
    void stopsAtTheRepeatUntilBoundary() {
        ReminderSchedule schedule = schedule("2026-01-01T02:00:00Z", VN, RepeatRule.DAILY, 1,
                null, null, "2026-01-10");

        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-09T02:00:00Z")))
                .contains(OffsetDateTime.parse("2026-01-10T02:00:00Z"));
        assertThat(schedule.nextOccurrenceAfter(OffsetDateTime.parse("2026-01-10T02:00:00Z")))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenUntilPrecedesTheAnchor() {
        ReminderSchedule schedule = schedule("2026-01-05T02:00:00Z", VN, RepeatRule.DAILY, 1,
                null, null, "2026-01-01");

        assertThat(schedule.nextOccurrenceAfter(null)).isEmpty();
    }

    @Test
    void weeklyStopsMidWeekAtUntil() {
        // Neo Thứ Hai 05/01, until rơi vào Thứ Tư 07/01 — Thứ Sáu cùng tuần không được phát.
        ReminderSchedule schedule = schedule("2026-01-05T02:00:00Z", VN, RepeatRule.WEEKLY, 1,
                "MONDAY,WEDNESDAY,FRIDAY", null, "2026-01-07");

        assertThat(walk(schedule, 5)).containsExactly(
                OffsetDateTime.parse("2026-01-05T02:00:00Z"),
                OffsetDateTime.parse("2026-01-07T02:00:00Z"));
    }

    // --------------------------------------------------------------------------- đổi giờ mùa

    /** "09:00 mỗi sáng" phải vẫn là 09:00 sau khi đổi giờ, dù mốc tuyệt đối lùi một giờ. */
    @Test
    void keepsTheLocalTimeAcrossSpringForward() {
        ReminderSchedule schedule = schedule("2026-03-28T08:00:00Z", PARIS, RepeatRule.DAILY, 1,
                null, null, null);

        List<OffsetDateTime> walked = walk(schedule, 2);
        assertThat(walked).containsExactly(
                OffsetDateTime.parse("2026-03-28T08:00:00Z"),
                OffsetDateTime.parse("2026-03-29T07:00:00Z"));
        assertThat(walked.get(1)).isAfter(walked.get(0));
    }

    /**
     * Giờ địa phương không tồn tại (02:30 của ngày nhảy giờ): đẩy tới trước đúng độ dài khoảng
     * nhảy. Thà kêu muộn một giờ còn hơn mất hẳn một ngày thuốc, và {@code occurrencesBetween} cho
     * ra cùng mốc nên lịch với thông báo vẫn khớp nhau.
     */
    @Test
    void shiftsForwardWhenTheLocalTimeDoesNotExist() {
        ReminderSchedule schedule = schedule("2026-03-28T01:30:00Z", PARIS, RepeatRule.DAILY, 1,
                null, null, null);

        List<OffsetDateTime> walked = walk(schedule, 2);
        // 2026-03-29T03:30+02:00 — 02:30 bị nuốt mất nên lùi sang 03:30.
        assertThat(walked.get(1)).isEqualTo(OffsetDateTime.parse("2026-03-29T01:30:00Z"));
        assertThat(walked.get(1)).isAfter(walked.get(0));
    }

    /** Giờ lặp lại hai lần (lùi giờ): thứ tự các mốc trong ngày vẫn tăng chặt. */
    @Test
    void keepsTimeOfDayOrderAcrossFallBack() {
        ReminderSchedule schedule = schedule("2026-10-25T00:30:00Z", PARIS, RepeatRule.DAILY, 1,
                null, "02:30,03:30", null);

        assertThat(walk(schedule, 2)).containsExactly(
                OffsetDateTime.parse("2026-10-25T00:30:00Z"),
                OffsetDateTime.parse("2026-10-25T02:30:00Z"));
    }

    // ------------------------------------------------------- hai đường phải cho cùng một tập

    /**
     * Chốt chặn quan trọng nhất: đi bộ {@code nextOccurrenceAfter} phải ra <strong>đúng</strong>
     * tập mà {@code occurrencesBetween} vẽ lên lịch, trên cả cửa sổ truy vấn rộng nhất
     * ({@code maxRangeDays = 366}).
     *
     * <p>Hai đường này phục vụ ba chỗ khác nhau — vẽ lịch, gửi thông báo, và validate "đánh dấu đã
     * uống". Lệch nhau thì không ai crash, chỉ là người dùng nhận thông báo cho một mốc mà lịch
     * không hiện và API từ chối đánh dấu.</p>
     */
    @Test
    void walkingAgreesWithOccurrencesBetweenForEveryRule() {
        for (NamedSchedule named : crossCheckCases()) {
            OffsetDateTime from = named.anchorDate().atStartOfDay(ZoneId.of(named.timezone()))
                    .toOffsetDateTime();
            OffsetDateTime to = from.plusDays(366);

            assertThat(walkUntil(named.schedule(), to)).as(named.name())
                    .containsExactlyElementsOf(named.schedule().occurrencesBetween(from, to));
        }
    }

    /** Mốc nào đã bắn thông báo thì phải đánh dấu "đã uống" được — cùng một định nghĩa lần lặp. */
    @Test
    void everyWalkedInstantIsAnOccurrence() {
        for (NamedSchedule named : crossCheckCases()) {
            for (OffsetDateTime at : walk(named.schedule(), 12)) {
                assertThat(named.schedule().isOccurrence(at)).as(named.name() + " @ " + at)
                        .isTrue();
            }
        }
    }

    /** Mốc đã-bắn là watermark của job; nó lùi lại một lần là gửi trùng một lần. */
    @Test
    void walkedInstantsAreStrictlyIncreasing() {
        for (NamedSchedule named : crossCheckCases()) {
            assertThat(walk(named.schedule(), 12)).as(named.name()).isSorted();
        }
    }

    // ------------------------------------------------------------------------------ bền vững

    /**
     * Chuỗi chạy khỏi miền ngày biểu diễn được phải ra rỗng, không ném: exception lọt ra thì
     * transaction của job rollback rồi thử lại đúng dòng đó mãi mãi.
     */
    @Test
    void returnsEmptyInsteadOfThrowingBeyondTheMaximumDate() {
        OffsetDateTime edge = OffsetDateTime.of(LocalDate.of(999_999_999, 12, 1),
                LocalTime.of(9, 0), ZoneOffset.UTC);
        for (RepeatRule rule : RepeatRule.values()) {
            CalendarReminderEntity reminder = entity(edge, "UTC");
            reminder.setRepeatRule(rule);
            reminder.setRepeatInterval(365);
            ReminderSchedule schedule = ReminderSchedule.of(reminder);

            assertThatCode(() -> assertThat(schedule.nextOccurrenceAfter(edge)).isEmpty())
                    .as(rule.name())
                    .doesNotThrowAnyException();
        }
    }

    // -------------------------------------------------------------------------------- helpers

    /**
     * WEEKLY với {@code interval} lớn: khẳng định bằng tính chất thay vì bằng một mốc viết tay, để
     * một phép cộng ngày sai trong test không tự xác nhận chính nó.
     */
    private static void assertWeeklyJumpsWholeIntervals(int interval) {
        ReminderSchedule schedule = weekly("2026-01-07T02:00:00Z", interval, "WEDNESDAY");
        OffsetDateTime first = OffsetDateTime.parse("2026-01-07T02:00:00Z");

        Optional<OffsetDateTime> next = schedule.nextOccurrenceAfter(first);

        assertThat(next).isPresent();
        LocalDate nextDate = next.get().atZoneSameInstant(ZoneId.of(VN)).toLocalDate();
        assertThat(nextDate.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(ChronoUnit.DAYS.between(LocalDate.parse("2026-01-07"), nextDate))
                .isEqualTo(interval * 7L);
        assertThat(schedule.isOccurrence(next.get())).isTrue();
    }

    private record NamedSchedule(String name, ReminderSchedule schedule, LocalDate anchorDate,
                                 String timezone) {
    }

    private static List<NamedSchedule> crossCheckCases() {
        return List.of(
                named("DAILY/1", "2026-01-01T02:00:00Z", VN, RepeatRule.DAILY, 1, null, null),
                named("DAILY/7", "2026-01-01T02:00:00Z", VN, RepeatRule.DAILY, 7, null, null),
                named("DAILY/90", "2026-01-01T02:00:00Z", VN, RepeatRule.DAILY, 90, null, null),
                named("WEEKLY/1 MON-WED-FRI", "2026-01-07T02:00:00Z", VN, RepeatRule.WEEKLY, 1,
                        "MONDAY,WEDNESDAY,FRIDAY", null),
                named("WEEKLY/3 SAT-SUN", "2026-01-07T02:00:00Z", VN, RepeatRule.WEEKLY, 3,
                        "SATURDAY,SUNDAY", null),
                named("MONTHLY/1 ngày 31", "2026-01-31T02:00:00Z", VN, RepeatRule.MONTHLY, 1,
                        null, null),
                named("MONTHLY/2 ngày 15", "2026-01-15T02:00:00Z", VN, RepeatRule.MONTHLY, 2,
                        null, null),
                named("DAILY/1 ba mốc giờ", "2026-01-01T02:00:00Z", VN, RepeatRule.DAILY, 1,
                        null, "08:00,13:00,20:00"),
                // Cắt qua cả hai lần đổi giờ của Paris trong một cửa sổ 366 ngày.
                named("DAILY/1 Paris", "2026-01-01T08:00:00Z", PARIS, RepeatRule.DAILY, 1,
                        null, null));
    }

    private static NamedSchedule named(String name, String startsAt, String timezone,
                                       RepeatRule rule, Integer interval, String daysOfWeek,
                                       String timesOfDay) {
        LocalDate anchorDate = OffsetDateTime.parse(startsAt)
                .atZoneSameInstant(ZoneId.of(timezone)).toLocalDate();
        return new NamedSchedule(name,
                schedule(startsAt, timezone, rule, interval, daysOfWeek, timesOfDay, null),
                anchorDate, timezone);
    }

    /** Đi bộ chuỗi: mỗi kết quả thành mốc đang đứng cho lần sau, đúng như job nhắc lịch làm. */
    private static List<OffsetDateTime> walk(ReminderSchedule schedule, int count) {
        List<OffsetDateTime> result = new ArrayList<>();
        OffsetDateTime watermark = null;
        for (int step = 0; step < count; step++) {
            Optional<OffsetDateTime> next = schedule.nextOccurrenceAfter(watermark);
            if (next.isEmpty()) {
                return result;
            }
            watermark = next.get();
            result.add(watermark);
        }
        return result;
    }

    private static List<OffsetDateTime> walkUntil(ReminderSchedule schedule, OffsetDateTime to) {
        List<OffsetDateTime> result = new ArrayList<>();
        OffsetDateTime watermark = null;
        while (true) {
            Optional<OffsetDateTime> next = schedule.nextOccurrenceAfter(watermark);
            if (next.isEmpty() || !next.get().isBefore(to)) {
                return result;
            }
            watermark = next.get();
            result.add(watermark);
        }
    }

    private static ReminderSchedule daily(String startsAt, int interval) {
        return schedule(startsAt, VN, RepeatRule.DAILY, interval, null, null, null);
    }

    private static ReminderSchedule weekly(String startsAt, int interval, String daysOfWeek) {
        return schedule(startsAt, VN, RepeatRule.WEEKLY, interval, daysOfWeek, null, null);
    }

    private static ReminderSchedule monthly(String startsAt, int interval) {
        return schedule(startsAt, VN, RepeatRule.MONTHLY, interval, null, null, null);
    }

    /** Null ở đâu nghĩa là cột đó NULL trong DB, đúng hình dạng một dòng thật. */
    private static ReminderSchedule schedule(String startsAt, String timezone, RepeatRule rule,
                                             Integer interval, String daysOfWeek,
                                             String timesOfDay, String until) {
        CalendarReminderEntity reminder = entity(OffsetDateTime.parse(startsAt), timezone);
        reminder.setRepeatRule(rule);
        reminder.setRepeatInterval(interval);
        reminder.setRepeatDaysOfWeek(daysOfWeek);
        reminder.setRepeatTimesOfDay(timesOfDay);
        reminder.setRepeatUntil(until == null ? null : LocalDate.parse(until));
        return ReminderSchedule.of(reminder);
    }

    private static CalendarReminderEntity entity(OffsetDateTime startsAt, String timezone) {
        CalendarReminderEntity reminder = new CalendarReminderEntity();
        reminder.setOwnerUserId("mom-1");
        reminder.setType(ReminderType.FOLLOW_UP);
        reminder.setTitle("Tái khám");
        reminder.setTimezone(timezone);
        reminder.setStartsAt(startsAt);
        return reminder;
    }
}
