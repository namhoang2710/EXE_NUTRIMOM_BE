package vn.nutrimom.admin.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.model.AdminQuery;
import static vn.nutrimom.admin.repository.AdminOperationsRepository.*;
import static vn.nutrimom.admin.service.AdminOperationsService.number;

@Repository
public class AdminDashboardMetricsRepository {
    private final AdminOperationsRepository reads;
    private final NamedParameterJdbcTemplate jdbc;
    public AdminDashboardMetricsRepository(AdminOperationsRepository reads, NamedParameterJdbcTemplate jdbc) {
        this.reads=reads;this.jdbc=jdbc;
    }
    public record CustomerMetrics(CustomerSnapshot snapshot,long currentMonthNew,long previousMonthNew) {}
    public CustomerSnapshot customers(OffsetDateTime at) { return customerMetrics(at).snapshot(); }
    public CustomerMetrics customerMetrics(OffsetDateTime at) {
        var month=at.atZoneSameInstant(AdminQuery.ZONE).toLocalDate().withDayOfMonth(1);
        var params=reads.parameters(at).addValue("monthStart",month.atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("previousStart",month.minusMonths(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
        var row=reads.one("""
                select count(*) total,
                  coalesce(sum(case when t.plan_tier='PLAN_99K' then 1 else 0 end),0) basic,
                  coalesce(sum(case when t.plan_tier='PLAN_399K' then 1 else 0 end),0) comprehensive,
                  coalesce(sum(case when u.created_at>=:monthStart and u.created_at<=:now then 1 else 0 end),0) current_new,
                  coalesce(sum(case when u.created_at>=:previousStart and u.created_at<:monthStart then 1 else 0 end),0) previous_new
                """+REGISTERED_FROM+" where "+USERS,params);
        long total=number(row,"total"),basic=number(row,"basic"),comprehensive=number(row,"comprehensive");
        return new CustomerMetrics(new CustomerSnapshot(at,total,total-basic-comprehensive,basic,comprehensive),
                number(row,"current_new"),number(row,"previous_new"));
    }
    public record Collections(long lifetimeAmount, long previousLifetimeAmount, MonthlyRevenue monthly) {}
    /** One bounded aggregate scan returns the lifetime sum and at most twelve calendar months. */
    public Collections collections(OffsetDateTime at) {
        LocalDate today=at.atZoneSameInstant(AdminQuery.ZONE).toLocalDate();
        var params=reads.parameters(at).addValue("monthStart",today.withDayOfMonth(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
        var sql=new StringBuilder("select coalesce(sum(p.amount),0) lifetime,coalesce(sum(case when p.paid_at<:monthStart then p.amount else 0 end),0) previous_lifetime");
        for(int month=1;month<=today.getMonthValue();month++) {
            LocalDate first=LocalDate.of(today.getYear(),month,1);
            params.addValue("start"+month,first.atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                    .addValue("end"+month,first.plusMonths(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
            String condition="p.paid_at>=:start"+month+" and p.paid_at<:end"+month;
            sql.append(",coalesce(sum(case when ").append(condition).append(" then p.amount else 0 end),0) amount_").append(month)
                    .append(",coalesce(sum(case when ").append(condition).append(" then 1 else 0 end),0) count_").append(month);
        }
        sql.append(" from app.payment_orders p where p.status='PAID' and p.paid_at<=:now");
        var row=reads.one(sql.toString(),params);
        var months=new java.util.ArrayList<MonthlyCollection>();
        for(int month=1;month<=today.getMonthValue();month++) {
            months.add(new MonthlyCollection(java.time.YearMonth.of(today.getYear(),month).toString(),
                    number(row,"amount_"+month),number(row,"count_"+month)));
        }
        return new Collections(number(row,"lifetime"),number(row,"previous_lifetime"),new MonthlyRevenue(today.getYear(),"ALL",List.copyOf(months)));
    }
    public List<CustomerSnapshot> dailyHistory(LocalDate from, OffsetDateTime at) {
        return reads.rows("""
                select * from (
                  select m.*,row_number() over (
                    partition by cast(captured_at at time zone 'Asia/Ho_Chi_Minh' as date)
                    order by captured_at desc,snapshot_hour desc) rn
                  from app.admin_customer_metrics m where captured_at>=:since and captured_at<=:now
                ) daily where rn=1 order by captured_at
                """,reads.parameters(at).addValue("since",from.atStartOfDay(AdminQuery.ZONE).toOffsetDateTime()),
                (r,n)->new CustomerSnapshot(time(r,"captured_at"),r.getLong("total_customers"),r.getLong("free_users"),
                        r.getLong("plan_99k_users"),r.getLong("plan_399k_users")));
    }
    public OffsetDateTime historyStartedAt(OffsetDateTime at) {
        return jdbc.queryForObject("select min(captured_at) started_at from app.admin_customer_metrics where captured_at<=:now",
                reads.parameters(at),(r,n)->time(r,"started_at"));
    }
    /** Insert first, then update only to a newer observation; safe across concurrent instances. */
    public void save(CustomerSnapshot snapshot) {
        var p=new MapSqlParameterSource().addValue("hour",snapshot.asOf().truncatedTo(ChronoUnit.HOURS))
                .addValue("at",snapshot.asOf()).addValue("total",snapshot.totalCustomers())
                .addValue("free",snapshot.freeUsers()).addValue("basic",snapshot.plan99kUsers())
                .addValue("comprehensive",snapshot.plan399kUsers());
        jdbc.update("""
                insert into app.admin_customer_metrics(snapshot_hour,captured_at,total_customers,free_users,plan_99k_users,plan_399k_users)
                values (:hour,:at,:total,:free,:basic,:comprehensive) on conflict do nothing
                """,p);
        jdbc.update("""
                update app.admin_customer_metrics set captured_at=:at,total_customers=:total,free_users=:free,
                  plan_99k_users=:basic,plan_399k_users=:comprehensive
                where snapshot_hour=:hour and captured_at<:at
                """,p);
    }
}
