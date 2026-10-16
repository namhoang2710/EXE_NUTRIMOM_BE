package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.nutrimom.nutrition.service.GestationalAge;

/**
 * TC04 và phép cộng ngày của tuổi thai, kiểm bằng test thuần — không database, không múi giờ.
 *
 * <p>Tách được như vậy chính là lý do {@code GestationalAge} nhận {@link LocalDate} đã quy đổi
 * thay vì tự đi hỏi múi giờ.</p>
 */
class GestationalAgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    /**
     * TC04 — giai đoạn đổi <strong>đúng ranh giới tuần</strong>, không lệch một ngày.
     *
     * <p>97 ngày là 13 tuần 6 ngày và vẫn thuộc giai đoạn 1; 98 ngày là 14 tuần 0 ngày và đã sang
     * giai đoạn 2. Mốc 27/28 tuần kiểm luôn ở đây vì nó dễ lệch theo cùng một kiểu.</p>
     */
    @ParameterizedTest(name = "{0} ngày = {1}t{2}n, giai đoạn {3}")
    @CsvSource({
            "0,   0,  0, 1",
            "96,  13, 5, 1",
            "97,  13, 6, 1",
            "98,  14, 0, 2",
            "99,  14, 1, 2",
            "195, 27, 6, 2",
            "196, 28, 0, 3",
            "280, 40, 0, 3",
    })
    void trimesterChangesExactlyAtTheWeekBoundary(int days, int weeks, int dayOfWeek, int trimester) {
        GestationalAge age = GestationalAge.of(days, TODAY, null);

        assertThat(age.days()).isEqualTo(days);
        assertThat(age.weeks()).isEqualTo(weeks);
        assertThat(age.dayOfWeek()).isEqualTo(dayOfWeek);
        assertThat(age.trimester()).isEqualTo(trimester);
    }

    @Test
    void oneCalendarDayAfterThirteenWeeksSixDaysIsAlreadyTheSecondTrimester() {
        LocalDate anchorDate = TODAY;
        int anchorDays = 97;

        GestationalAge sameDay = GestationalAge.of(
                GestationalAge.daysFromAnchor(anchorDays, anchorDate, TODAY), TODAY, null);
        GestationalAge nextDay = GestationalAge.of(
                GestationalAge.daysFromAnchor(anchorDays, anchorDate, TODAY.plusDays(1)),
                TODAY.plusDays(1), null);

        assertThat(sameDay.weeks()).isEqualTo(13);
        assertThat(sameDay.dayOfWeek()).isEqualTo(6);
        assertThat(sameDay.trimester()).isEqualTo(1);
        assertThat(nextDay.weeks()).isEqualTo(14);
        assertThat(nextDay.dayOfWeek()).isZero();
        assertThat(nextDay.trimester()).isEqualTo(2);
    }

    @Test
    void theAnchorStillCountsForwardWhenItLiesInThePast() {
        // Mốc ghi nhận cách đây 30 ngày thì hôm nay phải cộng đúng 30 ngày, không phải tính lại
        // từ đầu bằng một ngày dự sinh khác.
        int days = GestationalAge.daysFromAnchor(97, TODAY.minusDays(30), TODAY);

        assertThat(days).isEqualTo(127);
    }

    @Test
    void aDueDateThatHasPassedIsReportedWithoutAssumingBirth() {
        GestationalAge age = GestationalAge.of(285, TODAY, TODAY.minusDays(5));

        assertThat(age.dueDatePassed()).isTrue();
        assertThat(age.daysUntilDue()).isEqualTo(-5);
        // Vẫn là một tuổi thai hợp lệ: qua ngày dự sinh không đồng nghĩa đã sinh.
        assertThat(age.weeks()).isEqualTo(40);
    }

    @Test
    void aDueDateStillAheadIsNotFlagged() {
        GestationalAge age = GestationalAge.of(200, TODAY, TODAY.plusDays(80));

        assertThat(age.dueDatePassed()).isFalse();
        assertThat(age.daysUntilDue()).isEqualTo(80);
    }

    @Test
    void negativeGestationIsClampedInsteadOfProducingNegativeWeeks() {
        // Ngày neo trong tương lai (dữ liệu hỏng ở module thai kỳ) không được làm màn hình hiện
        // "-1 tuần"; kẹp về 0 và để phần còn lại của hệ thống đọc ra "chưa có dữ liệu".
        GestationalAge age = GestationalAge.of(-10, TODAY, null);

        assertThat(age.days()).isZero();
        assertThat(age.weeks()).isZero();
        assertThat(age.trimester()).isEqualTo(1);
    }

    @Test
    void theDueDateConversionUsesTheSameObstetricAnchorAsThePregnancyModule() {
        assertThat(GestationalAge.OBSTETRIC_DAYS).isEqualTo(280);
    }
}
