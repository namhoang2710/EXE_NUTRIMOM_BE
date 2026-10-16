package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import vn.nutrimom.admin.model.ExpertKpiPeriod;
import vn.nutrimom.common.exception.BusinessException;

class ExpertKpiPeriodTest {
    @Test void fullCalendarMonthsHandle31DaysFebruaryLeapYearsAndYearRollover() {
        for(String[] sample:new String[][]{{"2026-10-15","2026-10-31","2026-09-01","2026-09-30"},
                {"2026-02-15","2026-02-28","2026-01-01","2026-01-31"},
                {"2028-02-15","2028-02-29","2028-01-01","2028-01-31"},
                {"2027-01-15","2027-01-31","2026-12-01","2026-12-31"}}) {
            var clock=Clock.fixed(Instant.parse(sample[0]+"T05:00:00Z"),ZoneOffset.UTC);
            var p=ExpertKpiPeriod.resolve(null,null,clock);
            assertEquals(LocalDate.parse(sample[1]),p.toDate());
            assertEquals(LocalDate.parse(sample[2]),p.previousFromDate());
            assertEquals(LocalDate.parse(sample[3]),p.previousToDate());
            assertEquals(p,ExpertKpiPeriod.resolve(p.fromDate(),p.toDate(),clock));
        }
    }
    @Test void customRangeUsesPrecedingEqualNumberOfDaysAndRejectsInvalidInput() {
        var clock=Clock.fixed(Instant.parse("2026-10-15T05:00:00Z"),ZoneOffset.UTC);
        var p=ExpertKpiPeriod.resolve(LocalDate.of(2026,10,1),LocalDate.of(2026,10,15),clock);
        assertEquals(LocalDate.of(2026,9,16),p.previousFromDate());assertEquals(LocalDate.of(2026,9,30),p.previousToDate());
        assertThrows(BusinessException.class,()->ExpertKpiPeriod.resolve(null,LocalDate.of(2026,10,15),clock));
        assertThrows(BusinessException.class,()->ExpertKpiPeriod.resolve(LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),clock));
        assertThrows(BusinessException.class,()->ExpertKpiPeriod.resolve(LocalDate.of(2026,10,16),LocalDate.of(2026,10,31),clock));
        assertThrows(BusinessException.class,()->ExpertKpiPeriod.resolve(LocalDate.of(2025,1,1),LocalDate.of(2026,10,15),clock));
    }
}
