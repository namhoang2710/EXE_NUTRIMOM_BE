package vn.nutrimom.consultation;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import vn.nutrimom.consultation.domain.ConsultationTiming;
class ConsultationTimingTest {
    @Test void arrivalBoundariesIncludeFiveMinutesEarlyAndThreeMinutesLate() {
        Instant start=Instant.parse("2026-10-15T01:00:00Z");
        assertTrue(ConsultationTiming.onTime(start.minusSeconds(300),start));
        assertTrue(ConsultationTiming.onTime(start,start));assertTrue(ConsultationTiming.onTime(start.plusSeconds(180),start));
        assertFalse(ConsultationTiming.onTime(start.plusSeconds(181),start));assertFalse(ConsultationTiming.onTime(start.plusSeconds(900),start));
        assertFalse(ConsultationTiming.onTime(start.minusSeconds(301),start));assertFalse(ConsultationTiming.onTime(null,start));
    }
    @Test void deadlineNoticeUsesVietnamDayAndAbsoluteDate() {
        var opened=OffsetDateTime.parse("2026-10-15T02:00:00+07:00");var deadline=ConsultationTiming.acceptanceDeadline(opened);
        assertTrue(ConsultationTiming.deadlineMessage(deadline,opened).contains("08:30 hôm nay (15/10/2026)"));
        opened=OffsetDateTime.parse("2026-10-15T19:00:00+07:00");deadline=ConsultationTiming.acceptanceDeadline(opened);
        assertTrue(ConsultationTiming.deadlineMessage(deadline,opened).contains("08:30 ngày mai (16/10/2026)"));
    }
}
