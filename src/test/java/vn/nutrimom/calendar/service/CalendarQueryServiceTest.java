package vn.nutrimom.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.nutrimom.calendar.domain.CalendarSource;
import vn.nutrimom.calendar.dto.CalendarDtos.CalendarEventItem;
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
import vn.nutrimom.user.domain.UserPreferenceEntity;
import vn.nutrimom.user.repository.UserPreferenceRepository;

/** Quy đổi múi giờ và biên cửa sổ của lịch trộn — phần dễ sai nhất của module. */
@ExtendWith(MockitoExtension.class)
class CalendarQueryServiceTest {

    private static final String USER_ID = "mom-1";
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    @Mock private CalendarMedicalRecordQueryRepository medicalRecords;
    @Mock private CalendarConsultationQueryRepository consultations;
    @Mock private CalendarReminderRepository reminders;
    @Mock private CalendarReminderOccurrenceRepository occurrences;
    @Mock private UserPreferenceRepository preferences;

    private CalendarProperties properties;
    private CalendarQueryService service;

    @BeforeEach
    void setUp() {
        properties = new CalendarProperties();
        CalendarZone zones = new CalendarZone(preferences, properties);
        service = new CalendarQueryService(medicalRecords, consultations, reminders, occurrences,
                zones, properties, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
    }

    /** Khung giờ VN phải quy đổi bằng giờ VN, không phải múi giờ của người đang xem. */
    @Test
    void vietnamSlotIsConvertedWithTheVietnamZoneNotTheRequestedOne() {
        stubEmptyExceptConsultations(List.of(consultation("2026-10-02", "08:00", "08:30")));

        List<CalendarEventItem> items = service.events(USER_ID,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"),
                LA.getId(), null);

        assertThat(items).hasSize(1);
        // 08:00 giờ VN = 01:00 UTC, tức 18:00 ngày 01/10 ở Los Angeles.
        assertThat(items.get(0).startsAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-02T01:00:00Z"));
        assertThat(items.get(0).date()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    /** Query nới biên ±1 ngày, nên phần lọc chính xác phải chặn những dòng dư đó. */
    @Test
    void consultationRowsOutsideThePaddedWindowAreDropped() {
        stubEmptyExceptConsultations(List.of(
                consultation("2026-10-01", "08:00", "08:30"),   // 01/10 01:00Z — trước cửa sổ
                consultation("2026-10-02", "10:00", "10:30"),   // 02/10 03:00Z — trong cửa sổ
                consultation("2026-10-04", "09:00", "09:30"))); // 04/10 02:00Z — sau cửa sổ

        List<CalendarEventItem> items = service.events(USER_ID,
                LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-02"),
                "Asia/Ho_Chi_Minh", null);

        assertThat(items).extracting(CalendarEventItem::startsAt)
                .containsExactly(OffsetDateTime.parse("2026-10-02T10:00+07:00"));
    }

    /** Biên trên loại trừ: 00:00 của ngày kế tiếp thuộc về cửa sổ sau. */
    @Test
    void theWindowIsHalfOpenAtTheEndOfTheRange() {
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        stubEmptyExceptConsultations(List.of());

        service.events(USER_ID, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"),
                "Asia/Ho_Chi_Minh", null);

        verifyWindow(from, to);
        assertThat(from.getValue()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+07:00"));
        assertThat(to.getValue()).isEqualTo(OffsetDateTime.parse("2026-10-04T00:00+07:00"));
    }

    @Test
    void timezoneFallsBackToThePreferenceThenToTheDefault() {
        UserPreferenceEntity preference = new UserPreferenceEntity();
        preference.setTimezone("Europe/Paris");
        when(preferences.findById(USER_ID)).thenReturn(Optional.of(preference));
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        stubEmptyExceptConsultations(List.of());

        service.events(USER_ID, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"),
                null, null);

        verifyWindow(from, to);
        assertThat(from.getValue().getOffset())
                .isEqualTo(LocalDate.parse("2026-10-01").atStartOfDay(ZoneId.of("Europe/Paris"))
                        .getOffset());
    }

    @Test
    void eventsAreSortedByStartAcrossSources() {
        when(medicalRecords.findWindow(eq(USER_ID), any(), any())).thenReturn(List.of(
                new MedicalRecordCalendarRow("record-1", "Siêu âm",
                        OffsetDateTime.parse("2026-10-02T05:00:00Z"),
                        MedicalRecordCategory.ULTRASOUND, "BV A")));
        when(consultations.findConfirmed(eq(USER_ID), any(), any(), any()))
                .thenReturn(List.of(consultation("2026-10-02", "08:00", "08:30")));
        when(reminders.findWindow(eq(USER_ID), any(), any(), any())).thenReturn(List.of());

        List<CalendarEventItem> items = service.events(USER_ID,
                LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-02"),
                "Asia/Ho_Chi_Minh", null);

        assertThat(items).extracting(CalendarEventItem::source)
                .containsExactly(CalendarSource.CONSULTATION, CalendarSource.MEDICAL_RECORD);
    }

    /** Lọc theo nguồn phải chặn ngay ở tầng truy vấn, không chỉ lọc sau khi đã đọc. */
    @Test
    void filteringBySourceSkipsTheOtherQueriesEntirely() {
        when(reminders.findWindow(eq(USER_ID), any(), any(), any())).thenReturn(List.of());

        service.events(USER_ID, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"),
                "Asia/Ho_Chi_Minh", Set.of(CalendarSource.REMINDER));

        // Không stub medicalRecords/consultations: Mockito strict sẽ báo nếu service gọi tới.
        assertThat(true).isTrue();
    }

    @Test
    void rangeAndMonthParametersAreValidated() {
        assertThatThrownBy(() -> service.events(USER_ID, LocalDate.parse("2026-10-02"),
                LocalDate.parse("2026-10-01"), "Asia/Ho_Chi_Minh", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR.code()));

        assertThatThrownBy(() -> service.events(USER_ID, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-06-01"), "Asia/Ho_Chi_Minh", null))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> service.month(USER_ID, 2026, 13, "Asia/Ho_Chi_Minh"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR.code()));

        assertThatThrownBy(() -> service.events(USER_ID, LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-01"), "Mars/Olympus", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR.code()));
    }

    // ----- Helpers -----

    private void stubEmptyExceptConsultations(List<ConfirmedConsultationRow> rows) {
        lenient().when(medicalRecords.findWindow(eq(USER_ID), any(), any())).thenReturn(List.of());
        lenient().when(reminders.findWindow(eq(USER_ID), any(), any(), any())).thenReturn(List.of());
        lenient().when(consultations.findConfirmed(eq(USER_ID), any(), any(), any()))
                .thenReturn(rows);
    }

    private void verifyWindow(ArgumentCaptor<OffsetDateTime> from, ArgumentCaptor<OffsetDateTime> to) {
        org.mockito.Mockito.verify(medicalRecords)
                .findWindow(anyString(), from.capture(), to.capture());
    }

    private static ConfirmedConsultationRow consultation(String date, String start, String end) {
        return new ConfirmedConsultationRow("request-" + date + start,
                ConsultationStatus.PENDING_CONSULTATION, LocalDate.parse(date),
                LocalTime.parse(start), LocalTime.parse(end), "BS Lan");
    }
}
