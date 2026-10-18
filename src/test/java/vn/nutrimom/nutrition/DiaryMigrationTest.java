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
 * Thực thi file V64 và kiểm các ràng buộc của nó.
 *
 * <p>Cùng lý do và cùng giới hạn với các bài migration trước: integration test tắt Flyway và dựng
 * schema bằng {@code ddl-auto}, nên không bài nào chạy file .sql; bài này chạy trên H2 ở chế độ
 * PostgreSQL chứ không phải PostgreSQL thật, nên vẫn phải chạy migration thật một lượt trên
 * PostgreSQL trước khi merge.</p>
 *
 * <p>Ba thứ đáng kiểm nhất ở đây đều là chỗ database chặn một lỗi của lớp trên:</p>
 * <ul>
 *   <li><strong>{@code uq_diary_entries_request}</strong> — ghi trùng không chỉ thừa một dòng, nó
 *       làm tổng đã ăn của ngày gấp đôi (TC16).</li>
 *   <li><strong>{@code ck_diary_entries_shape}</strong> — một dòng vừa trỏ tới công thức vừa mang
 *       tên tự do sẽ mập mờ đúng ở chỗ nguy hiểm nhất: nó có số liệu dinh dưỡng hay không.</li>
 *   <li><strong>{@code ck_meal_feedbacks_expiry}</strong> — hạn gắn chặt với loại, nên không có
 *       đường nào tạo ra một "không thích" tự biến mất sau bảy ngày (QĐ-03).</li>
 * </ul>
 *
 * <p>Và một hành vi vòng đời: <strong>xóa kế hoạch không xóa nhật ký</strong>. Đó là bất biến 3
 * viết bằng SQL — bữa đã ăn là một sự việc đã xảy ra, không phải hệ quả của bản kế hoạch.</p>
 */
class DiaryMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V64__diary_and_feedback.sql");

    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_USER_ID = "33333333-3333-3333-3333-333333333333";
    private static final String PLAN_ITEM_ID = "88888888-8888-8888-8888-888888888888";

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        String url = "jdbc:h2:mem:nutrition_v64_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        db = DriverManager.getConnection(url, "sa", "");
        execute("CREATE SCHEMA IF NOT EXISTS app");
        execute("CREATE TABLE app.users (id VARCHAR(36) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.recipes (id VARCHAR(64) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.meal_plan_items (id VARCHAR(36) NOT NULL PRIMARY KEY)");

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
        execute("INSERT INTO app.recipes (id) VALUES ('rec.ga-kho-gung')");
        execute("INSERT INTO app.meal_plan_items (id) VALUES ('" + PLAN_ITEM_ID + "')");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Idempotency — TC16
    // -----------------------------------------------------------------------------------------

    @Test
    void theSameRequestIdTwiceForOneUserIsRejected() {
        assertThatThrownBy(() -> {
            insertRecipeEntry("entry-a", USER_ID, "req-1", "1.00");
            insertRecipeEntry("entry-b", USER_ID, "req-1", "1.00");
        }).isInstanceOf(SQLException.class);
    }

    /** Khóa là <em>cặp</em> (người dùng, request_id): hai người bấm cùng lúc không chặn nhau. */
    @Test
    void twoUsersMayShareARequestId() throws Exception {
        insertRecipeEntry("entry-a", USER_ID, "req-1", "1.00");
        insertRecipeEntry("entry-b", OTHER_USER_ID, "req-1", "1.00");

        assertThat(count("SELECT count(*) FROM app.diary_entries")).isEqualTo(2);
    }

    // -----------------------------------------------------------------------------------------
    // Hai dạng dòng
    // -----------------------------------------------------------------------------------------

    @Test
    void aRecipeRowCarryingFreeTextIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, recipe_id, recipe_version, recipe_snapshot,
                     actual_servings, custom_text, estimate_incomplete, request_id,
                     created_at, updated_at)
                VALUES ('entry-mixed', '%s', DATE '2026-10-14', 'LUNCH', 'rec.com-trang', 1,
                        '{}', 1.00, 'phở gánh đầu ngõ', FALSE, 'req-mixed',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    /**
     * Món ngoài bộ dữ liệu <strong>không được</strong> mang số liệu của một công thức nào.
     *
     * <p>Đây là "không tự sinh vi chất từ tên món" viết bằng ràng buộc: không có snapshot thì dòng
     * ấy không góp con số nào vào tổng, và không có đường nào gán cho nó một snapshot mượn.</p>
     */
    @Test
    void anOffCatalogRowCarryingASnapshotIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, recipe_snapshot, custom_text,
                     estimate_incomplete, request_id, created_at, updated_at)
                VALUES ('entry-borrowed', '%s', DATE '2026-10-14', 'LUNCH', '{}',
                        'phở gánh đầu ngõ', FALSE, 'req-borrowed',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    @Test
    void anOffCatalogRowWithoutATextIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, estimate_incomplete, request_id,
                     created_at, updated_at)
                VALUES ('entry-empty', '%s', DATE '2026-10-14', 'LUNCH', FALSE, 'req-empty',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    @Test
    void aRecipeRowWithoutServingsIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, recipe_id, recipe_version, recipe_snapshot,
                     estimate_incomplete, request_id, created_at, updated_at)
                VALUES ('entry-no-servings', '%s', DATE '2026-10-14', 'LUNCH', 'rec.com-trang', 1,
                        '{}', FALSE, 'req-no-servings', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    @Test
    void anOffCatalogRowIsAccepted() throws Exception {
        execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, custom_text, amount_text,
                     estimate_incomplete, request_id, created_at, updated_at)
                VALUES ('entry-custom', '%s', DATE '2026-10-14', 'SNACK', 'Bánh mì cô Ba',
                        'một ổ', FALSE, 'req-custom', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID));

        assertThat(count("SELECT count(*) FROM app.diary_entries WHERE recipe_snapshot IS NULL"))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------------
    // Giá trị hợp lệ
    // -----------------------------------------------------------------------------------------

    @Test
    void zeroServingsIsRejected() {
        assertThatThrownBy(() -> insertRecipeEntry("entry-zero", USER_ID, "req-zero", "0.00"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void anUnknownMealTypeIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, recipe_id, recipe_version, recipe_snapshot,
                     actual_servings, estimate_incomplete, request_id, created_at, updated_at)
                VALUES ('entry-brunch', '%s', DATE '2026-10-14', 'BRUNCH', 'rec.com-trang', 1,
                        '{}', 1.00, FALSE, 'req-brunch', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID))).isInstanceOf(SQLException.class);
    }

    /** Nửa phần là một giá trị hợp lệ, không phải một con số cần làm tròn (TC15). */
    @Test
    void halfAServingIsAccepted() throws Exception {
        insertRecipeEntry("entry-half", USER_ID, "req-half", "0.50");

        assertThat(count("SELECT count(*) FROM app.diary_entries WHERE actual_servings = 0.50"))
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------------
    // Vòng đời — bất biến 3
    // -----------------------------------------------------------------------------------------

    /**
     * Xóa mục kế hoạch chỉ bỏ cái <strong>nguồn</strong>, không bỏ bữa đã ăn.
     *
     * <p>CASCADE ở đây sẽ là bất biến 3 bị phá ngay ở tầng dữ liệu: sửa thực đơn sẽ lặng lẽ viết
     * lại nhật ký, và tổng đã ăn của một ngày trong quá khứ đổi mà không ai chạm vào nó.</p>
     */
    @Test
    void deletingThePlanItemKeepsTheDiaryEntryAndClearsOnlyItsSource() throws Exception {
        execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, recipe_id, recipe_version, recipe_snapshot,
                     actual_servings, source_plan_item_id, estimate_incomplete, request_id,
                     created_at, updated_at)
                VALUES ('entry-from-plan', '%s', DATE '2026-10-14', 'LUNCH', 'rec.com-trang', 1,
                        '{}', 0.50, '%s', FALSE, 'req-from-plan',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(USER_ID, PLAN_ITEM_ID));

        execute("DELETE FROM app.meal_plan_items WHERE id = '" + PLAN_ITEM_ID + "'");

        assertThat(count("SELECT count(*) FROM app.diary_entries")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app.diary_entries "
                + "WHERE source_plan_item_id IS NULL")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------------
    // Phản hồi — QĐ-03
    // -----------------------------------------------------------------------------------------

    @Test
    void aTemporaryFeedbackCarriesAnExpiry() throws Exception {
        insertFeedback("fb-hard", "HARD_NOW", "DATEADD('DAY', 7, CURRENT_TIMESTAMP)");

        assertThat(count("SELECT count(*) FROM app.meal_feedbacks WHERE expires_at IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    void aLastingFeedbackWithAnExpiryIsRejected() {
        assertThatThrownBy(() -> insertFeedback("fb-dislike", "DISLIKE",
                "DATEADD('DAY', 7, CURRENT_TIMESTAMP)")).isInstanceOf(SQLException.class);
    }

    @Test
    void aTemporaryFeedbackWithoutAnExpiryIsRejected() {
        assertThatThrownBy(() -> insertFeedback("fb-hard", "HARD_NOW", "NULL"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aLastingFeedbackWithoutAnExpiryIsAccepted() throws Exception {
        insertFeedback("fb-tasty", "TASTY", "NULL");

        assertThat(count("SELECT count(*) FROM app.meal_feedbacks WHERE expires_at IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void anUnknownFeedbackTypeIsRejected() {
        assertThatThrownBy(() -> insertFeedback("fb-odd", "ALLERGIC", "NULL"))
                .isInstanceOf(SQLException.class);
    }

    /** Bảng ghi theo kiểu nhật ký: đổi ý là một dòng mới, không đè lên dòng cũ. */
    @Test
    void theSameRecipeMayCollectSeveralFeedbacksOverTime() throws Exception {
        insertFeedback("fb-1", "TASTY", "NULL");
        insertFeedback("fb-2", "DISLIKE", "NULL");

        assertThat(count("SELECT count(*) FROM app.meal_feedbacks")).isEqualTo(2);
    }

    // -----------------------------------------------------------------------------------------
    // Hạ tầng
    // -----------------------------------------------------------------------------------------

    private void insertRecipeEntry(String id, String userId, String requestId, String servings)
            throws SQLException {
        execute("""
                INSERT INTO app.diary_entries
                    (id, user_id, local_date, meal_type, recipe_id, recipe_version, recipe_snapshot,
                     actual_servings, estimate_incomplete, request_id, created_at, updated_at)
                VALUES ('%s', '%s', DATE '2026-10-14', 'LUNCH', 'rec.com-trang', 1, '{}', %s,
                        FALSE, '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(id, userId, servings, requestId));
    }

    private void insertFeedback(String id, String type, String expiresAt) throws SQLException {
        execute("""
                INSERT INTO app.meal_feedbacks (id, user_id, recipe_id, type, created_at, expires_at)
                VALUES ('%s', '%s', 'rec.com-trang', '%s', CURRENT_TIMESTAMP, %s)
                """.formatted(id, USER_ID, type, expiresAt));
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
