package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Thực thi file V60 và kiểm các ràng buộc của nó.
 *
 * <p>Cùng lý do và cùng giới hạn với ba bài migration trước: integration test tắt Flyway và dựng
 * schema bằng {@code ddl-auto}, nên không bài nào chạy file .sql; bài này chạy trên H2 ở chế độ
 * PostgreSQL chứ không phải PostgreSQL thật, nên vẫn phải chạy migration thật một lượt trên Postgres
 * trước khi merge.</p>
 *
 * <p>Thứ đáng kiểm nhất ở đây là <strong>bộ bốn</strong> của {@code meal_slots} (QĐ-02) và ràng buộc
 * chặn lưu một kế hoạch {@code NO_MATCH} — hai thứ mà một lỗi lập trình ở lớp trên sẽ âm thầm đi qua
 * nếu database không nói gì.</p>
 */
class MealPlanMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V60__meal_plans.sql");

    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_USER_ID = "44444444-4444-4444-4444-444444444444";
    private static final String PLAN_ID = "55555555-5555-5555-5555-555555555555";
    private static final String SLOT_ID = "66666666-6666-6666-6666-666666666666";

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        String url = "jdbc:h2:mem:nutrition_v60_" + UUID.randomUUID()
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
        execute("INSERT INTO app.users (id) VALUES ('" + OTHER_USER_ID + "')");
        execute("INSERT INTO app.recipes (id) VALUES ('rec.com-trang')");
        insertPlan(PLAN_ID, "READY");
        insertSlot(SLOT_ID, "LUNCH", 1);
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    // -----------------------------------------------------------------------------------------
    // meal_slots — QĐ-02
    // -----------------------------------------------------------------------------------------

    @Test
    void theSlotKeyIsTheFourColumnTupleSoThreeSnacksFitInOneDay() throws Exception {
        insertSlot("snack-1", "SNACK", 1);
        insertSlot("snack-2", "SNACK", 2);
        insertSlot("snack-3", "SNACK", 3);

        assertThat(count("SELECT count(*) FROM app.meal_slots WHERE meal_type = 'SNACK'"))
                .isEqualTo(3);
    }

    @Test
    void theSameSnackSequenceTwiceIsRejected() {
        assertThatThrownBy(() -> {
            insertSlot("snack-a", "SNACK", 2);
            insertSlot("snack-b", "SNACK", 2);
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void aSecondLunchIsRejectedBecauseMainMealsAlwaysSitAtSequenceOne() {
        assertThatThrownBy(() -> insertSlot("lunch-2", "LUNCH", 2))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aFourthSnackIsRejected() {
        assertThatThrownBy(() -> insertSlot("snack-4", "SNACK", 4))
                .isInstanceOf(SQLException.class);
    }

    // -----------------------------------------------------------------------------------------
    // meal_plan_items
    // -----------------------------------------------------------------------------------------

    @Test
    void zeroServingsIsRejectedAtTheDatabaseToo() {
        assertThatThrownBy(() -> insertItem("item-zero", "0.00"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aNegativeServingCountIsRejected() {
        assertThatThrownBy(() -> insertItem("item-negative", "-1.00"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void anItemKeepsItsRecipeSnapshotAndMayHaveNoPriceAtAll() throws Exception {
        insertItem("item-ok", "1.50");

        try (Statement statement = db.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT servings, locked, cost_snapshot, nutrient_snapshot "
                             + "FROM app.meal_plan_items WHERE id = 'item-ok'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBigDecimal(1)).isEqualByComparingTo("1.50");
            assertThat(row.getBoolean(2)).isFalse();
            // Chưa quy đổi được giá là NULL, không phải 0 đồng.
            assertThat(row.getBigDecimal(3)).isNull();
            assertThat(row.getString(4)).contains("rec.com-trang");
        }
    }

    @Test
    void deletingAPlanTakesItsSlotsAndItemsWithIt() throws Exception {
        insertItem("item-cascade", "1.00");
        execute("DELETE FROM app.meal_plans WHERE id = '" + PLAN_ID + "'");

        assertThat(count("SELECT count(*) FROM app.meal_slots")).isZero();
        assertThat(count("SELECT count(*) FROM app.meal_plan_items")).isZero();
    }

    // -----------------------------------------------------------------------------------------
    // meal_plans
    // -----------------------------------------------------------------------------------------

    /** "Không tìm được thực đơn" là kết quả nghiệp vụ, không phải thứ để lưu thành kế hoạch. */
    @Test
    void aNoMatchResultCanNeverBeStoredAsASavedPlan() {
        assertThatThrownBy(() -> insertPlan("plan-no-match", "NO_MATCH"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void anEndDateBeforeTheStartDateIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.meal_plans (id, user_id, start_date, end_date, generation_status,
                        lifecycle_state, plan_version, profile_version, rules_version,
                        created_at, updated_at)
                VALUES ('plan-bad-range', '%s', DATE '2026-10-14', DATE '2026-10-13', 'READY',
                        'SAVED', 1, 3, 'nutrition-2026.10', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    // -----------------------------------------------------------------------------------------
    // meal_plan_previews — QĐ-01
    // -----------------------------------------------------------------------------------------

    @Test
    void theSameRequestIdFromTheSameUserCannotCreateASecondPreview() {
        assertThatThrownBy(() -> {
            insertPreview("preview-1", USER_ID, "req-demo-001", "READY");
            insertPreview("preview-2", USER_ID, "req-demo-001", "READY");
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void twoUsersMayUseTheSameRequestId() throws Exception {
        insertPreview("preview-mine", USER_ID, "req-demo-001", "READY");
        insertPreview("preview-theirs", OTHER_USER_ID, "req-demo-001", "READY");

        assertThat(count("SELECT count(*) FROM app.meal_plan_previews")).isEqualTo(2);
    }

    @Test
    void aPreviewThatExpiresBeforeItWasCreatedIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.meal_plan_previews (id, user_id, request_id, profile_version,
                        rules_version, status, payload, created_at, expires_at)
                VALUES ('preview-backwards', '%s', 'req-backwards', 3, 'nutrition-2026.10', 'READY',
                        '{}', CURRENT_TIMESTAMP, TIMESTAMP '2020-01-01 00:00:00')
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    @Test
    void aNoMatchPreviewCanNeverPointAtASavedPlan() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.meal_plan_previews (id, user_id, request_id, profile_version,
                        rules_version, status, payload, created_at, expires_at, saved_plan_id)
                VALUES ('preview-no-match', '%s', 'req-no-match', 3, 'nutrition-2026.10', 'NO_MATCH',
                        '{}', CURRENT_TIMESTAMP, DATEADD('MINUTE', 30, CURRENT_TIMESTAMP), '%s')
                """.formatted(USER_ID, PLAN_ID))).isInstanceOf(SQLException.class);
    }

    // -----------------------------------------------------------------------------------------

    private void insertPlan(String id, String generationStatus) throws SQLException {
        execute("""
                INSERT INTO app.meal_plans (id, user_id, start_date, end_date, generation_status,
                        lifecycle_state, plan_version, profile_version, rules_version,
                        created_at, updated_at)
                VALUES ('%s', '%s', DATE '2026-10-14', DATE '2026-10-14', '%s', 'SAVED', 1, 3,
                        'nutrition-2026.10', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(id, USER_ID, generationStatus));
    }

    private void insertSlot(String id, String mealType, int sequence) throws SQLException {
        execute("""
                INSERT INTO app.meal_slots (id, plan_id, local_date, meal_type, sequence,
                        template_code)
                VALUES ('%s', '%s', DATE '2026-10-14', '%s', %d, '%s:ROLE_SET')
                """.formatted(id, PLAN_ID, mealType, sequence, mealType));
    }

    private void insertItem(String id, String servings) throws SQLException {
        execute("""
                INSERT INTO app.meal_plan_items (id, slot_id, recipe_id, recipe_version, servings,
                        locked, nutrient_snapshot)
                VALUES ('%s', '%s', 'rec.com-trang', 1, %s, FALSE,
                        '{"recipeId":"rec.com-trang","servingsYield":4}')
                """.formatted(id, SLOT_ID, servings));
    }

    private void insertPreview(String id, String userId, String requestId, String status)
            throws SQLException {
        execute("""
                INSERT INTO app.meal_plan_previews (id, user_id, request_id, profile_version,
                        rules_version, status, payload, created_at, expires_at)
                VALUES ('%s', '%s', '%s', 3, 'nutrition-2026.10', '%s', '{}', CURRENT_TIMESTAMP,
                        DATEADD('MINUTE', 30, CURRENT_TIMESTAMP))
                """.formatted(id, userId, requestId, status));
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
