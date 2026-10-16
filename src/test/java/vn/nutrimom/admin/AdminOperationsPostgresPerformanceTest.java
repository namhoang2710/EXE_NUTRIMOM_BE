package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.service.AdminOperationsService;

/** Explicitly opt-in, rollback-only, and restricted to the isolated loopback QA database. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfSystemProperty(named="admin.performance.test",matches="true")
class AdminOperationsPostgresPerformanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminOperationsService service;
    @Autowired Environment env;
    @MockitoSpyBean NamedParameterJdbcTemplate reads;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;
    @Test void snapshotQueriesRemainBoundedWithTenThousandRequestsAndOrders() {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_qa",env.getProperty("spring.datasource.url"));
        jdbc.update("""
                insert into app.users(id,phone,display_name,status,created_at,updated_at,version,onboarding_status)
                select 'aaaa0000-0000-4000-8000-'||lpad(g::text,12,'0'),'perf-'||g,'Benchmark '||g,
                       'ACTIVE',current_timestamp-((g%30)||' days')::interval,current_timestamp,0,'COMPLETED'
                from generate_series(1,10000) g
                """);
        jdbc.update("""
                insert into app.user_roles(user_id,role)
                select id,'USER' from app.users where id like 'aaaa0000-%'
                """);
        jdbc.update("""
                insert into app.consultation_requests(id,user_id,specialty,assignment_type,status,created_at,updated_at,version)
                select 'bbbb0000-0000-4000-8000-'||lpad(g::text,12,'0'),
                       'aaaa0000-0000-4000-8000-'||lpad(g::text,12,'0'),
                       'OBSTETRICS','RANDOM','PENDING_EXPERT',current_timestamp-((g%30)||' days')::interval,current_timestamp,0
                from generate_series(1,10000) g
                """);
        jdbc.update("""
                insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,source,status,
                                               paid_at,created_at,updated_at,version)
                select 'cccc0000-0000-4000-8000-'||lpad(g::text,12,'0'),100000000+g,
                       'aaaa0000-0000-4000-8000-'||lpad(g::text,12,'0'),
                       'PLAN_99K',99000,'VND','REAL','PAID',current_timestamp-((g%30)||' days')::interval,
                       current_timestamp-((g%30)||' days')::interval,current_timestamp,0
                from generate_series(1,10000) g
                """);
        // Autovacuum cannot see these uncommitted fixtures. Give the planner representative
        // cardinalities before measuring; otherwise an empty QA DB selects quadratic joins.
        jdbc.execute("ANALYZE app.users, app.user_roles, app.consultation_requests, app.payment_orders");
        for(int i=0;i<3;i++)service.dashboard(null,null);
        long[] timings=new long[30];
        for(int i=0;i<timings.length;i++) {
            clearInvocations(reads);
            long start=System.nanoTime();var snapshot=service.dashboard(null,null);
            timings[i]=(System.nanoTime()-start)/1_000_000;
            assertEquals(10000,snapshot.kpis().expertPending());
            assertTrue(snapshot.attention().items().size()<=6);
            long count=mockingDetails(reads).getInvocations().stream()
                    .filter(call->call.getMethod().getName().startsWith("query")).count();
            assertTrue(count<=20,"Unbounded logical query count: "+count);
            assertEquals(10000,snapshot.customerSnapshot().totalCustomers());
            assertEquals(990000000,snapshot.overview().lifetimeRealCollectedAmount());
            assertEquals(30,snapshot.plan399kAdoption().dailySeries().size());
            if(i==timings.length-1)System.out.println("ADMIN_PERF logicalReadCalls="+count+" fixtureRequests=10000 fixtureOrders=10000");
        }
        Arrays.sort(timings);long p95=timings[28];
        System.out.println("ADMIN_PERF dashboardServiceP95Ms="+p95+" samples="+timings.length);
        assertTrue(p95<1000,"Dashboard DB-backed service p95 exceeded 1s: "+p95);
    }
}
