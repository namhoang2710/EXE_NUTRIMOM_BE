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
 * Thực thi file V63 và kiểm các ràng buộc của nó.
 *
 * <p>Cùng lý do và cùng giới hạn với các bài migration trước: integration test tắt Flyway và dựng
 * schema bằng {@code ddl-auto}, nên không bài nào chạy file .sql; bài này chạy trên H2 ở chế độ
 * PostgreSQL chứ không phải PostgreSQL thật, nên vẫn phải chạy migration thật một lượt trên
 * PostgreSQL trước khi merge.</p>
 *
 * <p>Thứ đáng kiểm nhất ở đây là <strong>khóa gộp ba cột</strong> {@code (list_id, ingredient_id,
 * food_state)} — nó là chỗ database chặn một lỗi lập trình ở lớp trên gộp gạo sống với cơm chín
 * (TC10) — và ràng buộc hai dạng dòng, vì một dòng vừa trỏ tới nguyên liệu vừa mang chữ tự do sẽ
 * mập mờ đúng ở chỗ nguy hiểm nhất: nó có tính vào dinh dưỡng hay không.</p>
 */
class ShoppingListMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V63__shopping_lists.sql");

    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String PLAN_ID = "55555555-5555-5555-5555-555555555555";
    private static final String LIST_ID = "77777777-7777-7777-7777-777777777777";

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        String url = "jdbc:h2:mem:nutrition_v63_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        db = DriverManager.getConnection(url, "sa", "");
        execute("CREATE SCHEMA IF NOT EXISTS app");
        execute("CREATE TABLE app.users (id VARCHAR(36) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.ingredients (id VARCHAR(64) NOT NULL PRIMARY KEY)");
        execute("""
                CREATE TABLE app.meal_plans (
                    id VARCHAR(36) NOT NULL PRIMARY KEY,
                    user_id VARCHAR(36) NOT NULL)
                """);

        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                execute(statement);
            }
        }

        execute("INSERT INTO app.users (id) VALUES ('" + USER_ID + "')");
        execute("INSERT INTO app.meal_plans (id, user_id) VALUES ('" + PLAN_ID + "', '"
                + USER_ID + "')");
        execute("INSERT INTO app.ingredients (id) VALUES ('ing.gao-te')");
        execute("INSERT INTO app.ingredients (id) VALUES ('ing.com-trang')");
        insertList(LIST_ID, "2026-10-14", "2026-10-14");
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Khóa gộp — TC10
    // -----------------------------------------------------------------------------------------

    @Test
    void rawRiceAndCookedRiceAreTwoRowsOfTheSameList() throws Exception {
        insertIngredientRow("row-raw", "ing.gao-te", "RAW", "300.00");
        insertIngredientRow("row-cooked", "ing.com-trang", "COOKED", "400.00");

        assertThat(count("SELECT count(*) FROM app.shopping_list_items")).isEqualTo(2);
    }

    /** Cùng một mã nguyên liệu ở hai trạng thái vẫn đi lọt — đó chính là điều khóa ba cột cho phép. */
    @Test
    void oneIngredientInTwoStatesFitsInOneList() throws Exception {
        insertIngredientRow("row-raw", "ing.gao-te", "RAW", "300.00");
        insertIngredientRow("row-dried", "ing.gao-te", "DRIED", "100.00");

        assertThat(count("SELECT count(*) FROM app.shopping_list_items")).isEqualTo(2);
    }

    @Test
    void theSameIngredientAndStateTwiceIsRejected() {
        assertThatThrownBy(() -> {
            insertIngredientRow("row-a", "ing.gao-te", "RAW", "300.00");
            insertIngredientRow("row-b", "ing.gao-te", "RAW", "100.00");
        }).isInstanceOf(SQLException.class);
    }

    // -----------------------------------------------------------------------------------------
    // Hai dạng dòng
    // -----------------------------------------------------------------------------------------

    @Test
    void anIngredientRowCarryingFreeTextIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.shopping_list_items
                    (id, list_id, ingredient_id, food_state, state, quantity, unit, custom_text,
                     created_at, updated_at)
                VALUES ('row-mixed', '%s', 'ing.gao-te', 'RAW', 'NEEDED', 100, 'g', 'giấy ăn',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(LIST_ID))).isInstanceOf(SQLException.class);
    }

    @Test
    void aCustomRowWithoutTextIsRejected() {
        assertThatThrownBy(() -> execute("""
                INSERT INTO app.shopping_list_items
                    (id, list_id, state, created_at, updated_at)
                VALUES ('row-empty', '%s', 'NEEDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(LIST_ID))).isInstanceOf(SQLException.class);
    }

    /** Dòng tự nhập không bị khóa gộp chạm tới: NULL không bằng NULL. */
    @Test
    void manyCustomRowsFitInOneList() throws Exception {
        insertCustomRow("row-1", "Giấy ăn");
        insertCustomRow("row-2", "Sữa tắm");
        insertCustomRow("row-3", "Trái cây tráng miệng");

        assertThat(count("SELECT count(*) FROM app.shopping_list_items WHERE ingredient_id IS NULL"))
                .isEqualTo(3);
    }

    // -----------------------------------------------------------------------------------------
    // Giá trị hợp lệ
    // -----------------------------------------------------------------------------------------

    @Test
    void anUnknownStateIsRejected() {
        assertThatThrownBy(() -> insertIngredientRow("row-odd", "ing.gao-te", "RAW", "100.00",
                "ALREADY_COOKED")).isInstanceOf(SQLException.class);
    }

    @Test
    void negativeQuantitiesAreRejected() {
        assertThatThrownBy(() -> insertIngredientRow("row-negative", "ing.gao-te", "RAW", "-1.00"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aListEndingBeforeItStartsIsRejected() {
        assertThatThrownBy(() -> insertList("list-backwards", "2026-10-16", "2026-10-14"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void theSamePlanAndRangeTwiceIsRejected() {
        assertThatThrownBy(() -> insertList("list-twice", "2026-10-14", "2026-10-14"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void anotherDateRangeOfTheSamePlanIsItsOwnList() throws Exception {
        insertList("list-other-range", "2026-10-14", "2026-10-16");

        assertThat(count("SELECT count(*) FROM app.shopping_lists")).isEqualTo(2);
    }

    // -----------------------------------------------------------------------------------------
    // Vòng đời
    // -----------------------------------------------------------------------------------------

    /**
     * Xóa kế hoạch thì danh sách đi theo.
     *
     * <p>Một danh sách không còn thực đơn nào ở sau lưng thì không tính lại được lượng cần, và một
     * danh sách không tính lại được là một danh sách sai ngay lần đổi món kế tiếp.</p>
     */
    @Test
    void deletingThePlanTakesTheListAndItsRowsWithIt() throws Exception {
        insertIngredientRow("row-raw", "ing.gao-te", "RAW", "300.00");
        insertCustomRow("row-custom", "Giấy ăn");

        execute("DELETE FROM app.meal_plans WHERE id = '" + PLAN_ID + "'");

        assertThat(count("SELECT count(*) FROM app.shopping_lists")).isZero();
        assertThat(count("SELECT count(*) FROM app.shopping_list_items")).isZero();
    }

    // -----------------------------------------------------------------------------------------
    // Hạ tầng
    // -----------------------------------------------------------------------------------------

    private void insertList(String id, String start, String end) throws SQLException {
        execute("""
                INSERT INTO app.shopping_lists
                    (id, user_id, plan_id, plan_version, start_date, end_date, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 1, DATE '%s', DATE '%s',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(id, USER_ID, PLAN_ID, start, end));
    }

    private void insertIngredientRow(String id, String ingredientId, String foodState,
                                     String quantity) throws SQLException {
        insertIngredientRow(id, ingredientId, foodState, quantity, "NEEDED");
    }

    private void insertIngredientRow(String id, String ingredientId, String foodState,
                                     String quantity, String state) throws SQLException {
        execute("""
                INSERT INTO app.shopping_list_items
                    (id, list_id, ingredient_id, food_state, food_group, display_name, state,
                     quantity, unit, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'GRAIN', 'Gạo', '%s', %s, 'g',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(id, LIST_ID, ingredientId, foodState, state, quantity));
    }

    private void insertCustomRow(String id, String text) throws SQLException {
        execute("""
                INSERT INTO app.shopping_list_items
                    (id, list_id, state, custom_text, created_at, updated_at)
                VALUES ('%s', '%s', 'NEEDED', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(id, LIST_ID, text));
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
