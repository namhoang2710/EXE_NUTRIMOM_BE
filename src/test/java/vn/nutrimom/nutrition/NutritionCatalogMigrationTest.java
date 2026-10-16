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
 * Thực thi file V57 và kiểm các ràng buộc của nó có chặn được dữ liệu sai.
 *
 * <p><strong>Vì sao cần:</strong> profile test của dự án tắt Flyway và dựng schema bằng
 * {@code ddl-auto} của Hibernate, nên không có bài kiểm nào chạy file .sql. Entity và migration có
 * thể trôi lệch nhau mà cả suite vẫn xanh — bài này đóng khe hở đó cho nhóm bảng của nhánh 1.</p>
 *
 * <p><strong>Giới hạn phải biết, đừng đọc test xanh thành "migration đã an toàn":</strong></p>
 * <ul>
 *   <li>Chạy trên H2 ở chế độ PostgreSQL, <em>không phải</em> PostgreSQL thật. Bắt được lỗi cú
 *       pháp, sai tên cột, khoá ngoại hỏng và CHECK không hoạt động; không bắt được khác biệt
 *       hành vi riêng của Postgres.</li>
 *   <li>Thực thi <em>trực tiếp</em> nội dung file chứ không qua Flyway. Chuỗi V1..V41 không chạy
 *       được trên H2 (V1 và V29 dùng partial index, cú pháp riêng của Postgres), mà chạy V57 qua
 *       Flyway trên H2 thì chính H2 đánh giá sai các CHECK đã tạo. Chạy trực tiếp kiểm đúng thứ
 *       đáng kiểm: nội dung SQL mình viết.</li>
 *   <li>Vẫn phải chạy migration thật một lượt trên Postgres trước khi merge.</li>
 * </ul>
 */
class NutritionCatalogMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V57__nutrition_catalog.sql");

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        String url = "jdbc:h2:mem:nutrition_v57_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        db = DriverManager.getConnection(url, "sa", "");
        execute("CREATE SCHEMA IF NOT EXISTS app");

        // Bỏ dòng comment TRƯỚC khi tách câu lệnh: một comment trong file có chứa dấu chấm phẩy
        // ("MEASURED = có đo; ESTIMATED = ..."), tách thô sẽ cắt giữa câu.
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                execute(statement);
            }
        }
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        execute("SHUTDOWN");
        db.close();
    }

    @Test
    void createsEveryCatalogTable() throws SQLException {
        List<String> expected = List.of("nutrition_sources", "nutrients", "allergens", "ingredients",
                "ingredient_aliases", "ingredient_allergens", "ingredient_components",
                "ingredient_nutrients", "price_estimates", "recipes", "recipe_meal_types",
                "recipe_ingredients", "meal_templates", "meal_template_roles",
                "nutrition_seed_state");

        List<String> actual = new ArrayList<>();
        try (Statement statement = db.createStatement();
             var rows = statement.executeQuery(
                     "SELECT LOWER(table_name) FROM information_schema.tables "
                             + "WHERE LOWER(table_schema) = 'app'")) {
            while (rows.next()) {
                actual.add(rows.getString(1));
            }
        }

        assertThat(actual).containsAll(expected);
    }

    @Test
    void acceptsAValidIngredient() throws SQLException {
        seedOneIngredient();

        assertThat(countOf("SELECT COUNT(*) FROM app.ingredients")).isEqualTo(1);
    }

    @Test
    void refusesANutrientRowThatSaysMissingButStillCarriesANumber() throws SQLException {
        seedOneIngredient();

        // Ràng buộc này giữ cho "thiếu dữ liệu" không lặng lẽ biến thành một con số (BR08).
        assertThatThrownBy(() -> execute(
                "INSERT INTO app.ingredient_nutrients "
                        + "(ingredient_id, nutrient_code, amount_per_100g, unit, status) "
                        + "VALUES ('ing.x', 'ENERGY', 100, 'kcal', 'MISSING')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAMeasuredNutrientRowWithNoNumber() throws SQLException {
        seedOneIngredient();

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.ingredient_nutrients "
                        + "(ingredient_id, nutrient_code, amount_per_100g, unit, status) "
                        + "VALUES ('ing.x', 'ENERGY', NULL, 'kcal', 'MEASURED')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void acceptsAMissingNutrientRowWithANullAmount() throws SQLException {
        seedOneIngredient();

        execute("INSERT INTO app.ingredient_nutrients "
                + "(ingredient_id, nutrient_code, amount_per_100g, unit, status) "
                + "VALUES ('ing.x', 'ENERGY', NULL, 'kcal', 'MISSING')");

        assertThat(countOf("SELECT COUNT(*) FROM app.ingredient_nutrients "
                + "WHERE amount_per_100g IS NULL")).isEqualTo(1);
    }

    @Test
    void refusesAnIngredientThatListsItselfAsItsOwnComponent() throws SQLException {
        seedOneIngredient();

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.ingredient_components (ingredient_id, child_ingredient_id) "
                        + "VALUES ('ing.x', 'ing.x')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAComponentPointingAtAnIngredientThatDoesNotExist() throws SQLException {
        seedOneIngredient();

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.ingredient_components (ingredient_id, child_ingredient_id) "
                        + "VALUES ('ing.x', 'ing.khong-ton-tai')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesARecipeWithZeroServingsYield() {
        assertThatThrownBy(() -> execute(
                "INSERT INTO app.recipes (id, name, version, role, servings_yield, minutes, steps) "
                        + "VALUES ('rec.x', 'Món', 1, 'MAIN_DISH', 0, 10, 'B1')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAnUnknownFoodGroupSoTheShoppingScreenNeverGrowsAnOrphanSection() {
        assertThatThrownBy(() -> execute(
                "INSERT INTO app.ingredients "
                        + "(id, canonical_name, food_state, food_group, allergen_status) "
                        + "VALUES ('ing.y', 'Thứ lạ', 'RAW', 'RAU_CU', 'KNOWN')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAnUnknownMealTypeOnARecipe() throws SQLException {
        execute("INSERT INTO app.recipes (id, name, version, role, servings_yield, minutes, steps) "
                + "VALUES ('rec.x', 'Món', 1, 'MAIN_DISH', 1, 10, 'B1')");

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.recipe_meal_types (recipe_id, meal_type) "
                        + "VALUES ('rec.x', 'BRUNCH')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void keepsTheSeedStateTableToASingleRow() throws SQLException {
        execute("INSERT INTO app.nutrition_seed_state (id, rules_version, applied_at) "
                + "VALUES ('X', 'v1', CURRENT_TIMESTAMP)");

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.nutrition_seed_state (id, rules_version, applied_at) "
                        + "VALUES ('Y', 'v2', CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
    }

    private void seedOneIngredient() throws SQLException {
        execute("INSERT INTO app.nutrients (code, name, unit) "
                + "VALUES ('ENERGY', 'Năng lượng', 'kcal')");
        execute("INSERT INTO app.ingredients "
                + "(id, canonical_name, food_state, food_group, allergen_status) "
                + "VALUES ('ing.x', 'Nguyên liệu', 'RAW', 'GRAIN', 'KNOWN')");
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
