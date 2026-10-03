package vn.nutrimom.assistant;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class AssistantSchemaMigrationTest {
    @Test
    void newMigrationCreatesConstraintsAndCascadesWithoutOrphaningPrivateHistory() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:assistant_migration_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            execute(db, "CREATE SCHEMA app");
            execute(db, "CREATE TABLE app.users (id VARCHAR(36) PRIMARY KEY)");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("db/migration/V32__assistant.sql"));
            execute(db, "INSERT INTO app.users(id) VALUES ('owner')");
            execute(db, "INSERT INTO app.assistant_preferences(user_id) VALUES ('owner')");
            execute(db, "INSERT INTO app.assistant_conversations(id,owner_user_id,title,context_version,created_at,updated_at) VALUES ('conversation','owner','Test',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            String message = "INSERT INTO app.assistant_messages(id,conversation_id,client_message_id,role,content,created_at) VALUES ";
            execute(db, message + "('question','conversation','client','USER','Question',CURRENT_TIMESTAMP)");
            execute(db, message + "('answer','conversation','client','ASSISTANT','Answer',CURRENT_TIMESTAMP)");
            assertThrows(SQLException.class, () -> execute(db, message + "('duplicate','conversation','client','USER','Question',CURRENT_TIMESTAMP)"));
            assertThrows(SQLException.class, () -> execute(db, "INSERT INTO app.assistant_preferences(user_id) VALUES ('someone-else')"));
            assertEquals(1, count(db, "assistant_quota"));
            execute(db, "DELETE FROM app.users WHERE id='owner'");
            assertEquals(0, count(db, "assistant_preferences"));
            assertEquals(0, count(db, "assistant_conversations"));
            assertEquals(0, count(db, "assistant_messages"));
            assertEquals(1, count(db, "assistant_quota"));
        }
    }
    @Test
    void automaticContextMigrationUpdatesUntouchedDefaultsAndPreservesExistingChoices() throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:assistant_defaults_" + UUID.randomUUID() + ";MODE=PostgreSQL")) {
            execute(db, "CREATE SCHEMA app");
            execute(db, "CREATE TABLE app.users (id VARCHAR(36) PRIMARY KEY)");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("db/migration/V32__assistant.sql"));
            execute(db, "INSERT INTO app.users(id) VALUES ('untouched'),('opted-out'),('new')");
            execute(db, "INSERT INTO app.assistant_preferences(user_id) VALUES ('untouched')");
            execute(db, "INSERT INTO app.assistant_preferences(user_id,context_version) VALUES ('opted-out',2)");
            ScriptUtils.executeSqlScript(db, new ClassPathResource("db/migration/V33__assistant_automatic_context.sql"));
            execute(db, "INSERT INTO app.assistant_preferences(user_id) VALUES ('new')");
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM app.assistant_preferences ORDER BY user_id")) {
                assertTrue(rows.next()); assertEquals("new", rows.getString("user_id"));
                assertTrue(rows.getBoolean("use_profile")); assertTrue(rows.getBoolean("use_pregnancy")); assertTrue(rows.getBoolean("use_medical_records")); assertFalse(rows.getBoolean("cloud_consent"));
                assertTrue(rows.next()); assertEquals("opted-out", rows.getString("user_id"));
                assertFalse(rows.getBoolean("use_profile")); assertEquals(2, rows.getLong("context_version"));
                assertTrue(rows.next()); assertEquals("untouched", rows.getString("user_id"));
                assertTrue(rows.getBoolean("use_profile")); assertEquals(1, rows.getLong("context_version")); assertEquals(1, rows.getLong("version")); assertFalse(rows.getBoolean("cloud_consent"));
            }
        }
    }
    private void execute(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) { statement.execute(sql); }
    }
    private int count(Connection db, String table) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM app." + table)) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }
}
