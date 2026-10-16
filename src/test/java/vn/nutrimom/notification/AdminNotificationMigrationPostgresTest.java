package vn.nutrimom.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Runs the complete migration chain in a dedicated empty localhost QA database, not the app DB. */
@EnabledIfSystemProperty(named = "admin.notification.migration.test", matches = "true")
class AdminNotificationMigrationPostgresTest {
    @Test void upgradesLegacyNotificationsWithoutGeneratingHistoricalEventsOrChangingReadState() throws Exception {
        String url = System.getProperty("admin.notification.migration.url",
                "jdbc:postgresql://127.0.0.1:55439/admin_notification_flyway");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55439/admin_notification_flyway")) {
            throw new IllegalArgumentException("This test requires its dedicated localhost QA database");
        }
        Flyway before = Flyway.configure().dataSource(url, "adminqa", "").schemas("app")
                .defaultSchema("app").locations("classpath:db/migration").target("47")
                .cleanDisabled(false).load();
        // The exact URL guard above restricts cleanup to this disposable QA database.
        before.clean();
        before.migrate();
        String user = UUID.randomUUID().toString();
        String legacy = UUID.randomUUID().toString();
        String personal = UUID.randomUUID().toString();
        try (var connection = DriverManager.getConnection(url, "adminqa", "")) {
            try (var insert = connection.prepareStatement("""
                    insert into app.users(id,phone,display_name,status,created_at,updated_at)
                    values (?,?,'Migration QA','ACTIVE',now(),now())
                    """)) {
                insert.setString(1, user); insert.setString(2, "qa-" + UUID.randomUUID().toString().substring(0, 12));
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    insert into app.notifications
                        (id,user_id,type,title,body,deep_link,dedup_key,read_at,created_at,updated_at,version)
                    values (?,?,'CONTACT','QA','Safe',?,?,now(),now(),now(),0)
                    """)) {
                insert.setString(1, legacy); insert.setString(2, user);
                insert.setString(3, "nutrimom://admin/contact-requests/" + UUID.randomUUID());
                insert.setString(4, "ADMIN_CONTACT_NEW:" + user + ":" + UUID.randomUUID()); insert.executeUpdate();
                insert.setString(1, personal); insert.setString(3, "nutrimom://contact-requests/" + UUID.randomUUID());
                insert.setString(4, null); insert.executeUpdate();
            }
            String legacyRequest=UUID.randomUUID().toString();
            try(var insert=connection.prepareStatement("insert into app.consultation_requests(id,user_id,specialty,assignment_type,status,created_at,updated_at,version) values (?,?,'HEALTH','RANDOM','PENDING_EXPERT',now(),now(),0)")) {
                insert.setString(1,legacyRequest);insert.setString(2,user);insert.executeUpdate();
            }
            String priorRead;
            try (var query = connection.prepareStatement("select read_at::text from app.notifications where id=?")) {
                query.setString(1, legacy);
                try (var row = query.executeQuery()) { row.next(); priorRead = row.getString(1); }
            }
            var migrated = Flyway.configure().dataSource(url, "adminqa", "").schemas("app")
                    .defaultSchema("app").locations("classpath:db/migration").target("48").load();
            assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(migrated.migrate().migrationsExecuted).isZero();
            try (var query = connection.prepareStatement("select audience,category,read_at::text from app.notifications where id=?")) {
                query.setString(1, legacy);
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("ADMIN");
                    assertThat(row.getString(2)).isEqualTo("SUPPORT"); assertThat(row.getString(3)).isEqualTo(priorRead);
                }
                query.setString(1, personal);
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("USER");
                    assertThat(row.getString(2)).isEqualTo("GENERAL");
                }
            }
            try (var statement = connection.createStatement();
                    var row = statement.executeQuery("select count(*) from app.admin_notification_outbox")) {
                row.next(); assertThat(row.getLong(1)).isZero();
            }
            var latest = Flyway.configure().dataSource(url, "adminqa", "").schemas("app")
                    .defaultSchema("app").locations("classpath:db/migration").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            try(var query=connection.prepareStatement("select acceptance_opened_at,accepted_at,acceptance_deadline_at,acceptance_policy_version,preferred_date,scheduled_starts_at,scheduled_ends_at from app.consultation_requests where id=?")) {
                query.setString(1,legacyRequest);
                try(var row=query.executeQuery()) {assertThat(row.next()).isTrue();assertThat(row.getObject(1)).isNull();assertThat(row.getObject(2)).isNull();for(int i=3;i<=7;i++) assertThat(row.getObject(i)).isNull();}
            }
        } finally {
            before.clean();
        }
    }
}
