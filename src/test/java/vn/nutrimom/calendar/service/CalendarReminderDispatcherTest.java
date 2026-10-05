package vn.nutrimom.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.nutrimom.calendar.domain.CalendarReminderEntity;
import vn.nutrimom.calendar.domain.ReminderType;
import vn.nutrimom.calendar.domain.RepeatRule;
import vn.nutrimom.calendar.repository.CalendarReminderRepository;
import vn.nutrimom.config.CalendarProperties;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

/** Tính idempotent của job nhắc lịch: claim thắng thì gửi, thua thì im; chuỗi lặp thì bắn tiếp. */
@ExtendWith(MockitoExtension.class)
class CalendarReminderDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
    private static final String REMINDER_ID = "reminder-1";

    @Mock private CalendarReminderRepository reminders;
    @Mock private NotificationService notifications;

    private CalendarReminderDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        dispatcher = new CalendarReminderDispatcher(reminders, notifications,
                new CalendarProperties(), clock);
    }

    @Test
    void publishesOnceWhenTheClaimSucceeds() {
        when(reminders.findById(REMINDER_ID))
                .thenReturn(Optional.of(reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"))));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        verify(notifications).publish(eq("mom-1"), eq(NotificationType.REMINDER),
                anyString(), anyString(), eq("nutrimom://calendar/reminders/" + REMINDER_ID),
                eq("CALENDAR_REMINDER"), eq(REMINDER_ID));
    }

    /** Mốc một lần đã bắn thì chuỗi hết, không còn gì để giành. */
    @Test
    void skipsAnAlreadyFiredOneOffWithoutClaiming() {
        CalendarReminderEntity reminder = reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"));
        reminder.setLastFiredOccurrence(reminder.getStartsAt());
        when(reminders.findById(REMINDER_ID)).thenReturn(Optional.of(reminder));

        dispatcher.dispatch(REMINDER_ID);

        verify(reminders, never()).claim(anyString(), any(), any(), any());
        verifyNoInteractions(notifications);
    }

    /** Hai instance cùng quét: bên thua claim không được gửi bản sao thứ hai. */
    @Test
    void doesNotPublishWhenAnotherInstanceClaimedFirst() {
        when(reminders.findById(REMINDER_ID))
                .thenReturn(Optional.of(reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"))));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(0);

        dispatcher.dispatch(REMINDER_ID);

        verifyNoInteractions(notifications);
    }

    /**
     * Mốc đã trôi qua quá lâu: vẫn dời mốc đã-bắn để dòng này không bị quét lại mãi, nhưng không
     * bắn "sắp tới giờ" cho một buổi hẹn của tuần trước.
     */
    @Test
    void claimsSilentlyWhenTheOccurrenceIsLongPast() {
        when(reminders.findById(REMINDER_ID))
                .thenReturn(Optional.of(reminder(OffsetDateTime.parse("2026-09-20T04:00:00Z"))));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        verify(reminders).claim(eq(REMINDER_ID), any(), any(), any());
        verifyNoInteractions(notifications);
    }

    /** Biên: quá hạn đúng bằng ngưỡng vẫn còn gửi. */
    @Test
    void publishesAtTheExactStalenessBoundary() {
        when(reminders.findById(REMINDER_ID))
                .thenReturn(Optional.of(reminder(NOW.atOffset(ZoneOffset.UTC).minusHours(24))));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        verify(notifications).publish(anyString(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    /**
     * Chuỗi lặp: bắn mốc hôm nay rồi dời {@code remind_at} sang mốc ngày mai — nếu không, nhắc nhở
     * hằng ngày chỉ kêu đúng một lần rồi im mãi.
     */
    @Test
    void recurringReminderAdvancesToTheNextOccurrence() {
        CalendarReminderEntity reminder = reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"));
        reminder.setRepeatRule(RepeatRule.DAILY);
        reminder.setRepeatInterval(1);
        when(reminders.findById(REMINDER_ID)).thenReturn(Optional.of(reminder));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        ArgumentCaptor<OffsetDateTime> occurrence = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> nextRemindAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reminders).claim(eq(REMINDER_ID), occurrence.capture(), nextRemindAt.capture(), any());
        assertThat(occurrence.getValue()).isEqualTo(OffsetDateTime.parse("2026-10-01T04:00:00Z"));
        // Mốc kế tiếp là 11:00 giờ VN hôm sau (= 04:00Z), trừ đi 60 phút nhắc trước.
        assertThat(nextRemindAt.getValue()).isEqualTo(OffsetDateTime.parse("2026-10-02T03:00:00Z"));
        verify(notifications).publish(anyString(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    /** Cách ngày: bỏ qua đúng một ngày giữa hai lần. */
    @Test
    void everyOtherDayJumpsTwoDaysAhead() {
        CalendarReminderEntity reminder = reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"));
        reminder.setRepeatRule(RepeatRule.DAILY);
        reminder.setRepeatInterval(2);
        when(reminders.findById(REMINDER_ID)).thenReturn(Optional.of(reminder));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        ArgumentCaptor<OffsetDateTime> nextRemindAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reminders).claim(eq(REMINDER_ID), any(), nextRemindAt.capture(), any());
        assertThat(nextRemindAt.getValue()).isEqualTo(OffsetDateTime.parse("2026-10-03T03:00:00Z"));
    }

    /**
     * Chu kỳ thưa (27 tháng) vẫn phải dời được {@code remind_at} sang mốc kế tiếp.
     *
     * <p>Trước đây lần lặp kế tiếp được tìm bằng cách dò từng ngày tới trần 800 ngày, nên mọi chuỗi
     * xa hơn thế — WEEKLY từ 115 tuần, MONTHLY từ 27 tháng — bị coi như đã hết: dòng đó "đỗ" lại
     * với {@code remind_at} null và không bao giờ kêu, dù lịch vẫn vẽ đủ mốc.</p>
     */
    @Test
    void sparseMonthlySeriesStillAdvancesToTheNextOccurrence() {
        CalendarReminderEntity reminder = reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"));
        reminder.setRepeatRule(RepeatRule.MONTHLY);
        reminder.setRepeatInterval(27);
        when(reminders.findById(REMINDER_ID)).thenReturn(Optional.of(reminder));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        ArgumentCaptor<OffsetDateTime> nextRemindAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reminders).claim(eq(REMINDER_ID), any(), nextRemindAt.capture(), any());
        // 11:00 giờ VN ngày 01/01/2029 (= 04:00Z), trừ đi 60 phút nhắc trước.
        assertThat(nextRemindAt.getValue()).isEqualTo(OffsetDateTime.parse("2029-01-01T03:00:00Z"));
    }

    /** Chuỗi đã hết hạn lặp: dọn {@code remind_at} về null để thôi quét dòng đó. */
    @Test
    void endedSeriesClearsTheNextRemindAt() {
        CalendarReminderEntity reminder = reminder(OffsetDateTime.parse("2026-10-01T04:00:00Z"));
        reminder.setRepeatRule(RepeatRule.DAILY);
        reminder.setRepeatInterval(1);
        reminder.setRepeatUntil(java.time.LocalDate.parse("2026-10-01"));
        when(reminders.findById(REMINDER_ID)).thenReturn(Optional.of(reminder));
        when(reminders.claim(eq(REMINDER_ID), any(), any(), any())).thenReturn(1);

        dispatcher.dispatch(REMINDER_ID);

        ArgumentCaptor<OffsetDateTime> nextRemindAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reminders).claim(eq(REMINDER_ID), any(), nextRemindAt.capture(), any());
        assertThat(nextRemindAt.getValue()).isNull();
    }

    @Test
    void doesNothingWhenTheReminderIsGone() {
        when(reminders.findById(REMINDER_ID)).thenReturn(Optional.empty());

        dispatcher.dispatch(REMINDER_ID);

        verifyNoInteractions(notifications);
    }

    private static CalendarReminderEntity reminder(OffsetDateTime startsAt) {
        CalendarReminderEntity reminder = new CalendarReminderEntity();
        reminder.setOwnerUserId("mom-1");
        reminder.setType(ReminderType.FOLLOW_UP);
        reminder.setTitle("Tái khám");
        reminder.setTimezone("Asia/Ho_Chi_Minh");
        reminder.setStartsAt(startsAt);
        reminder.setRemindMinutesBefore(60);
        reminder.setRemindAt(startsAt.minusMinutes(60));
        return reminder;
    }
}
