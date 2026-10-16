package vn.nutrimom.admin.model;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;

/** Schedule availability is a calendar plan, including planned leave later this month. */
public final class ExpertKpiPeriod {
    private ExpertKpiPeriod() { }
    public static Period resolve(LocalDate from,LocalDate to,Clock clock) {
        LocalDate today=LocalDate.now(clock.withZone(AdminQuery.ZONE));
        YearMonth month=YearMonth.from(today);
        if(from==null && to==null) {from=month.atDay(1);to=month.atEndOfMonth();}
        if(from==null || to==null || from.getYear()<1900 || to.getYear()>9998 || from.isAfter(to)
                || from.isAfter(today) || to.isAfter(month.atEndOfMonth()) || ChronoUnit.DAYS.between(from,to)>=366)
            throw AdminQuery.invalid("fromDate","Chọn cặp ngày hợp lệ, tối đa 366 ngày và không vượt cuối tháng hiện tại.");
        boolean fullMonth=from.getDayOfMonth()==1 && YearMonth.from(from).equals(YearMonth.from(to))
                && to.equals(YearMonth.from(from).atEndOfMonth());
        LocalDate previousTo=from.minusDays(1);
        LocalDate previousFrom=fullMonth?YearMonth.from(from).minusMonths(1).atDay(1)
                :from.minusDays(ChronoUnit.DAYS.between(from,to)+1);
        return new Period(from,to,previousFrom,previousTo);
    }
}
