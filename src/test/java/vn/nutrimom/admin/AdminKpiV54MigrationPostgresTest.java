package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository;
import vn.nutrimom.admin.repository.AdminOperationsRepository;
import vn.nutrimom.admin.realtime.AdminRealtimeRepository;
import vn.nutrimom.admin.service.AdminExpertPerformanceService;

/** Upgrade both deployed V53 shapes in a disposable, explicitly guarded database. */
@EnabledIfSystemProperty(named="admin.kpi.v54.migration.test",matches="true")
class AdminKpiV54MigrationPostgresTest {
    private static final String URL="jdbc:postgresql://127.0.0.1:55439/admin_kpi_v54_migration";
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(URL,"adminqa","").schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }
    @Test void repairsEarlierV53WithoutInventingTrackingOrChangingConsultations() throws Exception {
        verifyUpgrade(true);
    }
    @Test void upgradesCompleteV53AndKeepsExistingEvidence() throws Exception {
        verifyUpgrade(false);
    }
    private void verifyUpgrade(boolean earlierShape) throws Exception {
        assertEquals("jdbc:postgresql://127.0.0.1:55439/admin_kpi_v54_migration",URL);
        var old=flyway("53");old.clean();
        try {
            old.migrate();
            try(var connection=DriverManager.getConnection(URL,"adminqa","");var sql=connection.createStatement()) {
                var jdbc=new NamedParameterJdbcTemplate(new DriverManagerDataSource(URL,"adminqa",""));
                var service=new AdminExpertPerformanceService(new AdminExpertPerformanceRepository(jdbc),
                        new AdminOperationsRepository(jdbc),new AdminRealtimeRepository(jdbc),Clock.systemUTC());
                if(earlierShape) {
                    sql.execute("alter table app.consultation_monitor_state drop column tracking_started_at");
                    sql.execute("alter table app.consultation_requests drop column attendance_finalized_at");
                    var failure=assertThrows(org.springframework.jdbc.BadSqlGrammarException.class,
                            ()->service.snapshot(null,null,null,null,true,2));
                    assertEquals("42703",failure.getSQLException().getSQLState());
                } else {
                    sql.execute("update app.consultation_monitor_state set tracking_started_at='2026-10-15T08:00:00+07:00'");
                }
                long requests;
                try(var r=sql.executeQuery("select count(*) from app.consultation_requests")){r.next();requests=r.getLong(1);}
                var latest=flyway("latest");assertEquals(3,latest.migrate().migrationsExecuted);
                assertEquals(0,latest.migrate().migrationsExecuted);
                assertTrue(latest.validateWithResult().validationSuccessful);
                var snapshot=service.snapshot(null,null,null,null,true,2);
                assertEquals(2,snapshot.calculationVersion());assertEquals(6,snapshot.axes().size());
                try(var r=sql.executeQuery("select tracking_started_at from app.consultation_monitor_state where id=1")) {
                    assertTrue(r.next());if(earlierShape)assertNull(r.getObject(1));else assertNotNull(r.getObject(1));
                }
                try(var r=sql.executeQuery("select count(*) from app.consultation_requests")){r.next();assertEquals(requests,r.getLong(1));}
                sql.executeQuery("select attendance_finalized_at from app.consultation_requests limit 0").close();
                sql.executeQuery("select entry_kpi_finalized_at from app.consultation_requests limit 0").close();
                try(var r=sql.executeQuery("select count(*) from app.expert_consultation_checkins")){r.next();assertEquals(0,r.getLong(1));}
                try(var r=sql.executeQuery("select to_regclass('app.ix_consultation_monitor_due')")){r.next();assertNotNull(r.getObject(1));}
            }
        } finally {old.clean();}
    }
}
