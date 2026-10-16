package vn.nutrimom.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.model.AdminQuery;
import vn.nutrimom.admin.repository.AdminDashboardMetricsRepository;

@Service
public class AdminDashboardMetricsService {
    private final AdminDashboardMetricsRepository repository;
    private final Clock clock;
    public AdminDashboardMetricsService(AdminDashboardMetricsRepository repository,Clock clock) {
        this.repository=repository;this.clock=clock;
    }
    public record Metrics(CustomerSnapshot customers, long lifetimeAmount, MonthlyRevenue monthly, PlanAdoption adoption,
            long previousLifetimeAmount,long currentMonthNew,long previousMonthNew) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Metrics read(OffsetDateTime at) {
        var customerMetrics=repository.customerMetrics(at);var customers=customerMetrics.snapshot();var collections=repository.collections(at);
        LocalDate today=at.atZoneSameInstant(AdminQuery.ZONE).toLocalDate();
        LocalDate previousDay=today.withDayOfMonth(1).minusDays(1),start=today.minusDays(29);
        var byDay=new HashMap<LocalDate,CustomerSnapshot>();
        repository.dailyHistory(start.isBefore(previousDay)?start:previousDay,at)
                .forEach(row->byDay.put(row.asOf().atZoneSameInstant(AdminQuery.ZONE).toLocalDate(),row));
        var previous=byDay.get(previousDay);
        BigDecimal currentRatio=ratio(customers),previousRatio=previous==null?null:ratio(previous);
        String status=currentRatio==null?"NO_CUSTOMERS":previousRatio==null?"INSUFFICIENT_HISTORY":"AVAILABLE";
        // Missing and zero-denominator comparisons do not get fabricated baselines.
        if(!"AVAILABLE".equals(status)) {previous=null;previousRatio=null;}
        var series=new ArrayList<AdoptionDay>();
        for(int i=0;i<30;i++) {
            LocalDate day=start.plusDays(i);var sample=day.equals(today)?customers:byDay.get(day);
            series.add(new AdoptionDay(day,sample==null?null:sample.asOf(),sample==null?null:ratio(sample)));
        }
        var adoption=new PlanAdoption(currentRatio,previousRatio,
                previousRatio==null?null:currentRatio.subtract(previousRatio),previous==null?null:previous.asOf(),
                repository.historyStartedAt(at),status,java.util.List.copyOf(series));
        return new Metrics(customers,collections.lifetimeAmount(),collections.monthly(),adoption,
                collections.previousLifetimeAmount(),customerMetrics.currentMonthNew(),customerMetrics.previousMonthNew());
    }
    // The single aggregate is consistent; READ_COMMITTED lets concurrent upserts observe the winning row.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void capture() {
        var at=OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        repository.save(repository.customers(at));
    }
    private static BigDecimal ratio(CustomerSnapshot snapshot) {
        return snapshot.totalCustomers()==0?null:BigDecimal.valueOf(snapshot.plan399kUsers()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(snapshot.totalCustomers()),6,RoundingMode.HALF_UP);
    }
}
