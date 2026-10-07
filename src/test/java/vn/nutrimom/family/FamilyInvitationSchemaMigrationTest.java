package vn.nutrimom.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * V37 chạy thật, vì bộ test thường dùng {@code ddl-auto: create-drop} với Flyway tắt — nghĩa là
 * không có bài nào khác chạm tới file migration, trong khi production dùng {@code validate}.
 */
class FamilyInvitationSchemaMigrationTest {

    @Test
    void deliveryMigrationBackfillsAcceptedInvitationsAndGuardsTheNewColumns() throws Exception {
        try (Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:family_invite_migration_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            execute(db, "CREATE SCHEMA app");
            execute(db, "CREATE TABLE app.users (id VARCHAR(36) PRIMARY KEY)");
            // V8 không chạy được trên H2 (partial index "CREATE UNIQUE INDEX ... WHERE" là cú
            // pháp riêng của Postgres), nên dựng lại đúng phần bảng mà V37 sẽ sửa.
            execute(db, """
                    CREATE TABLE app.family_invitations (
                        id VARCHAR(36) NOT NULL PRIMARY KEY,
                        family_group_id VARCHAR(36) NOT NULL,
                        invited_phone VARCHAR(20) NULL,
                        invited_email VARCHAR(255) NULL,
                        token_hash VARCHAR(64) NOT NULL UNIQUE,
                        relationship VARCHAR(30) NOT NULL,
                        scopes TEXT NOT NULL,
                        expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        accepted_at TIMESTAMP WITH TIME ZONE NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                    """);
            insertInvitation(db, "already-accepted", "hash-1", "CURRENT_TIMESTAMP");
            insertInvitation(db, "still-pending", "hash-2", "NULL");

            ScriptUtils.executeSqlScript(db,
                    new ClassPathResource("db/migration/V37__family_invitation_delivery.sql"));

            // Lời mời đã dùng trước migration phải mang đúng trạng thái, không nằm lại ở PENDING.
            assertEquals("ACCEPTED", status(db, "already-accepted"));
            assertEquals("PENDING", status(db, "still-pending"));

            assertThrows(SQLException.class, () -> execute(db,
                    "UPDATE app.family_invitations SET status='EXPIRED' WHERE id='still-pending'"));
            assertThrows(SQLException.class, () -> execute(db,
                    "UPDATE app.family_invitations SET delivery_status='SMS' WHERE id='still-pending'"));

            execute(db, "UPDATE app.family_invitations SET status='REVOKED',"
                    + " delivery_status='SKIPPED', revoked_at=CURRENT_TIMESTAMP"
                    + " WHERE id='still-pending'");
            assertEquals("REVOKED", status(db, "still-pending"));
        }
    }

    /**
     * V38 chuẩn hoá {@code invited_email} về chữ thường.
     *
     * <p>Câu UPDATE đó là thứ duy nhất làm cho giả định "so sánh bằng là đủ" của
     * {@code findReceived} đúng với dữ liệu tạo trước nó — nếu nó không chạy, lời mời cũ sẽ không
     * bao giờ hiện trong hộp thư người nhận, và không có bài test nào khác chạm tới file này.</p>
     */
    @Test
    void recipientLookupMigrationNormalisesLegacyEmailsToLowercase() throws Exception {
        try (Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:family_invite_recipient_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            execute(db, "CREATE SCHEMA app");
            execute(db, "CREATE TABLE app.users (id VARCHAR(36) PRIMARY KEY)");
            execute(db, """
                    CREATE TABLE app.family_invitations (
                        id VARCHAR(36) NOT NULL PRIMARY KEY,
                        family_group_id VARCHAR(36) NOT NULL,
                        invited_phone VARCHAR(20) NULL,
                        invited_email VARCHAR(255) NULL,
                        token_hash VARCHAR(64) NOT NULL UNIQUE,
                        relationship VARCHAR(30) NOT NULL,
                        scopes TEXT NOT NULL,
                        expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        accepted_at TIMESTAMP WITH TIME ZONE NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                    """);
            insertInvitation(db, "mixed-case", "hash-3", "NULL", "Mai.Anh@Example.COM");
            insertInvitation(db, "already-lower", "hash-4", "NULL", "an@example.com");
            insertInvitation(db, "phone-only", "hash-5", "NULL", null);

            ScriptUtils.executeSqlScript(db, new ClassPathResource(
                    "db/migration/V38__family_invitation_recipient_lookup.sql"));

            assertEquals("mai.anh@example.com", invitedEmail(db, "mixed-case"));
            assertEquals("an@example.com", invitedEmail(db, "already-lower"));
            // NULL phải đi qua nguyên vẹn: tài khoản đăng ký bằng OTP không có email.
            assertEquals(null, invitedEmail(db, "phone-only"));
        }
    }

    private String invitedEmail(Connection db, String id) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT invited_email FROM app.family_invitations WHERE id='" + id + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void insertInvitation(Connection db, String id, String tokenHash, String acceptedAt,
                                  String invitedEmail) throws SQLException {
        execute(db, "INSERT INTO app.family_invitations(id,family_group_id,invited_email,"
                + "token_hash,relationship,scopes,expires_at,accepted_at,created_at) VALUES ('"
                + id + "','group'," + (invitedEmail == null ? "NULL" : "'" + invitedEmail + "'")
                + ",'" + tokenHash + "','PARTNER','SHARED_CALENDAR',"
                + "CURRENT_TIMESTAMP," + acceptedAt + ",CURRENT_TIMESTAMP)");
    }

    private void insertInvitation(Connection db, String id, String tokenHash, String acceptedAt)
            throws SQLException {
        execute(db, "INSERT INTO app.family_invitations(id,family_group_id,invited_email,"
                + "token_hash,relationship,scopes,expires_at,accepted_at,created_at) VALUES ('"
                + id + "','group','a@example.com','" + tokenHash + "','PARTNER','SHARED_CALENDAR',"
                + "CURRENT_TIMESTAMP," + acceptedAt + ",CURRENT_TIMESTAMP)");
    }

    private String status(Connection db, String id) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT status FROM app.family_invitations WHERE id='" + id + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void execute(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
