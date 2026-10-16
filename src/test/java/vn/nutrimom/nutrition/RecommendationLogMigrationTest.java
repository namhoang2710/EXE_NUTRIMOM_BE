package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Thực thi file V59 và kiểm các ràng buộc của nó.
 *
 * <p>Cùng lý do và cùng giới hạn với hai bài migration trước: profile test tắt Flyway và dựng
 * schema bằng {@code ddl-auto}, nên không bài nào chạy file .sql; chạy trên H2 ở chế độ PostgreSQL
 * chứ không phải PostgreSQL thật; vẫn phải chạy migration thật một lượt trên Postgres trước khi
 * merge.</p>
 */
class RecommendationLogMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V59__recommendation_logs.sql");

    private static final String LOG_ID = "33333333-3333-3333-3333-333333333333";
    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        String url = "jdbc:h2:mem:nutrition_v59_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        db = DriverManager.getConnection(url, "sa", "");
        execute("CREATE SCHEMA IF NOT EXISTS app");
        execute("CREATE TABLE app.users (id VARCHAR(36) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.recipes (id VARCHAR(64) NOT NULL PRIMARY KEY)");

        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                execute(statement);
            }
        }
        execute("INSERT INTO app.users (id) VALUES ('" + USER_ID + "')");
        execute("INSERT INTO app.recipes (id) VALUES ('rec.x')");
        insertLog("READY");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        execute("SHUTDOWN");
        db.close();
    }

    @Test
    void createsBothLogTables() throws SQLException {
        List<String> actual = new ArrayList<>();
        try (Statement statement = db.createStatement();
             var rows = statement.executeQuery(
                     "SELECT LOWER(table_name) FROM information_schema.tables "
                             + "WHERE LOWER(table_schema) = 'app'")) {
            while (rows.next()) {
                actual.add(rows.getString(1));
            }
        }

        assertThat(actual).contains("recommendation_logs", "recommendation_log_entries");
    }

    /**
     * Bất biến 2 được khóa ở tầng database: món chưa qua lọc không mang điểm tổng.
     *
     * <p>Một lỗi lập trình làm điểm rò sang món bị loại sẽ dừng ở đây chứ không lặng lẽ vào nhật
     * ký rồi thành bằng chứng sai lúc bảo vệ.</p>
     */
    @Test
    void aBlockedDishCannotCarryATotalScore() {
        assertThatThrownBy(() -> execute(entry("BLOCKED", "84.500", "1")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aBlockedDishCannotCarryARank() {
        assertThatThrownBy(() -> execute(entry("BLOCKED", null, "1")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aBlockedDishIsStillRecordedWithItsReason() throws SQLException {
        execute(entry("BLOCKED", null, null));

        assertThat(countOf("SELECT COUNT(*) FROM app.recommendation_log_entries "
                + "WHERE eligibility = 'BLOCKED' AND score_total IS NULL")).isEqualTo(1);
    }

    @Test
    void anEligibleDishKeepsItsFiveComponentScores() throws SQLException {
        execute("INSERT INTO app.recommendation_log_entries "
                + "(log_id, recipe_id, recipe_version, eligibility, rank, score_nutrition, "
                + " score_preference, score_variety, score_budget, score_convenience, "
                + " score_total, nutrition_status, reason_codes) "
                + "VALUES ('" + LOG_ID + "', 'rec.x', 1, 'ELIGIBLE', 1, 80, 95, 70, 90, 85, "
                + "84.500, 'PARTIAL', 'FAVORITE')");

        assertThat(countOf("SELECT COUNT(*) FROM app.recommendation_log_entries "
                + "WHERE score_total = 84.500 AND score_nutrition = 80")).isEqualTo(1);
    }

    /** Điểm thành phần để NULL được: tiêu chí không dùng được khác hẳn 0 điểm. */
    @Test
    void anUnusedCriterionIsStoredAsNullNotZero() throws SQLException {
        execute("INSERT INTO app.recommendation_log_entries "
                + "(log_id, recipe_id, recipe_version, eligibility, rank, score_preference, "
                + " score_total, nutrition_status) "
                + "VALUES ('" + LOG_ID + "', 'rec.x', 1, 'ELIGIBLE', 1, 50, 50.000, 'PARTIAL')");

        assertThat(countOf("SELECT COUNT(*) FROM app.recommendation_log_entries "
                + "WHERE score_budget IS NULL AND score_nutrition IS NULL")).isEqualTo(1);
    }

    @Test
    void refusesAnUnknownEligibilityValue() {
        assertThatThrownBy(() -> execute(entry("MAYBE", null, null)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAStatusOutsideReadyAndNoMatch() {
        assertThatThrownBy(() -> execute(
                "INSERT INTO app.recommendation_logs "
                        + "(id, user_id, profile_version, rules_version, seed, meal_type, status, "
                        + " created_at) "
                        + "VALUES ('44444444-4444-4444-4444-444444444444', '" + USER_ID + "', "
                        + "1, 'v1', 0, 'LUNCH', 'READY_WITH_NOTES', CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
    }

    /** NO_MATCH là kết quả nghiệp vụ hợp lệ và phải ghi được như mọi lượt khác. */
    @Test
    void aNoMatchRunIsRecordedJustLikeAnyOther() throws SQLException {
        execute("INSERT INTO app.recommendation_logs "
                + "(id, user_id, profile_version, rules_version, seed, meal_type, status, "
                + " reason_codes, criteria_used, created_at) "
                + "VALUES ('55555555-5555-5555-5555-555555555555', '" + USER_ID + "', "
                + "3, 'v1', 0, 'DINNER', 'NO_MATCH', 'NO_MATCH', '', CURRENT_TIMESTAMP)");

        assertThat(countOf("SELECT COUNT(*) FROM app.recommendation_logs "
                + "WHERE status = 'NO_MATCH'")).isEqualTo(1);
    }

    @Test
    void theSameRequestIdCanAppearTwiceBecauseItIsNotAnIdempotencyKey() throws SQLException {
        // Gợi ý là thao tác đọc; request_id chỉ để tra ngược khi đi tìm lỗi.
        execute("INSERT INTO app.recommendation_logs "
                + "(id, user_id, request_id, profile_version, rules_version, seed, meal_type, "
                + " status, created_at) "
                + "VALUES ('66666666-6666-6666-6666-666666666666', '" + USER_ID + "', 'req-1', "
                + "1, 'v1', 0, 'LUNCH', 'READY', CURRENT_TIMESTAMP)");
        execute("INSERT INTO app.recommendation_logs "
                + "(id, user_id, request_id, profile_version, rules_version, seed, meal_type, "
                + " status, created_at) "
                + "VALUES ('77777777-7777-7777-7777-777777777777', '" + USER_ID + "', 'req-1', "
                + "1, 'v1', 0, 'LUNCH', 'READY', CURRENT_TIMESTAMP)");

        assertThat(countOf("SELECT COUNT(*) FROM app.recommendation_logs "
                + "WHERE request_id = 'req-1'")).isEqualTo(2);
    }

    @Test
    void aDishAppearsAtMostOncePerRun() throws SQLException {
        execute(entry("ELIGIBLE", "50.000", "1"));

        assertThatThrownBy(() -> execute(entry("ELIGIBLE", "60.000", "2")))
                .isInstanceOf(SQLException.class);
    }

    // -------------------------------------------------------------------------------------

    private void insertLog(String status) throws SQLException {
        execute("INSERT INTO app.recommendation_logs "
                + "(id, user_id, request_id, profile_version, rules_version, seed, meal_type, "
                + " status, reason_codes, criteria_used, created_at) "
                + "VALUES ('" + LOG_ID + "', '" + USER_ID + "', 'req-0', 2, 'nutrition-seed-v2', "
                + "0, 'LUNCH', '" + status + "', '', 'N,P,V', CURRENT_TIMESTAMP)");
    }

    private String entry(String eligibility, String total, String rank) {
        return "INSERT INTO app.recommendation_log_entries "
                + "(log_id, recipe_id, recipe_version, eligibility, rank, score_total, "
                + " nutrition_status, reason_codes) "
                + "VALUES ('" + LOG_ID + "', 'rec.x', 1, '" + eligibility + "', "
                + (rank == null ? "NULL" : rank) + ", "
                + (total == null ? "NULL" : total) + ", 'PARTIAL', 'ALLERGEN_MATCH')";
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private int countOf(String sql) throws SQLException {
        try (Statement statement = db.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
