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
 * Thực thi file V58 và kiểm các ràng buộc của nó có chặn được dữ liệu sai.
 *
 * <p>Cùng lý do và cùng giới hạn với {@code NutritionCatalogMigrationTest}: profile test tắt
 * Flyway và dựng schema bằng {@code ddl-auto}, nên không bài nào chạy file .sql; chạy trên H2 ở
 * chế độ PostgreSQL chứ không phải PostgreSQL thật; vẫn phải chạy migration thật một lượt trên
 * Postgres trước khi merge.</p>
 *
 * <p>Khác một điểm: V58 có khóa ngoại trỏ sang bảng của V4 và V57, nên {@link #applyMigration()}
 * dựng sẵn <em>bản rút gọn</em> của những bảng cha đó. Bản rút gọn chỉ cần đủ cột để khóa ngoại
 * gắn được — kiểm nội dung các bảng cha là việc của migration sinh ra chúng.</p>
 */
class NutritionProfileMigrationTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V58__nutrition_profile.sql");

    private static final String PROFILE_ID = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID = "22222222-2222-2222-2222-222222222222";

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        String url = "jdbc:h2:mem:nutrition_v58_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        db = DriverManager.getConnection(url, "sa", "");
        execute("CREATE SCHEMA IF NOT EXISTS app");
        createParentTables();

        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                execute(statement);
            }
        }
        seedParentRows();
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        execute("SHUTDOWN");
        db.close();
    }

    @Test
    void createsEveryProfileTable() throws SQLException {
        List<String> expected = List.of("eating_preferences", "user_allergens",
                "eating_preference_hard_exclusions", "eating_preference_disliked_ingredients",
                "eating_preference_tolerance_tags", "eating_preference_meal_types",
                "user_recipe_preferences");

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

    /**
     * QĐ-03: bảng sở thích món <strong>không có</strong> cột hết hạn.
     *
     * <p>Đặc tả gốc có {@code expiresAt} ở cả bảng này lẫn {@code meal_feedbacks}; thêm lại vào
     * đây là làm sống lại đúng chỗ trùng lặp đã bỏ, và "tôi không muốn thấy món này nữa" sẽ âm
     * thầm hết hạn sau vài tuần.</p>
     */
    @Test
    void userRecipePreferencesHasNoExpiresAtColumn() throws SQLException {
        List<String> columns = columnsOf("user_recipe_preferences");

        assertThat(columns).doesNotContain("expires_at");
        assertThat(columns).contains("user_id", "recipe_id", "preference_type");
    }

    @Test
    void aRecipeCarriesExactlyOneAttitudePerUser() throws SQLException {
        insertProfile();
        execute(preference("rec.x", "FAVORITE"));

        assertThatThrownBy(() -> execute(preference("rec.x", "BLOCKED")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAnUnknownPreferenceType() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute(preference("rec.x", "MAYBE")))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAPreferenceForARecipeThatDoesNotExist() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute(preference("rec.khong-ton-tai", "FAVORITE")))
                .isInstanceOf(SQLException.class);
    }

    /** Ba trạng thái dị ứng là một tập đóng — "có/không" kiểu boolean không lọt vào được (BR01). */
    @Test
    void refusesAnAllergyStatusOutsideTheThreeDeclaredStates() {
        assertThatThrownBy(() -> execute(
                "INSERT INTO app.eating_preferences "
                        + "(profile_id, allergy_status, profile_version, created_at, updated_at) "
                        + "VALUES ('" + PROFILE_ID + "', 'YES', 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void anAllergenCanOnlyBeDeclaredOncePerProfile() throws SQLException {
        insertProfile();
        execute("INSERT INTO app.user_allergens (profile_id, allergen_id) "
                + "VALUES ('" + PROFILE_ID + "', 'alg.x')");

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.user_allergens (profile_id, allergen_id) "
                        + "VALUES ('" + PROFILE_ID + "', 'alg.x')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAnAllergenThatIsNotInTheCatalog() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.user_allergens (profile_id, allergen_id) "
                        + "VALUES ('" + PROFILE_ID + "', 'alg.khong-ton-tai')"))
                .isInstanceOf(SQLException.class);
    }

    /**
     * Mọi cột của bước 3, 4 và 5 đều bỏ trống được — bỏ qua bước vẫn phải lưu được hồ sơ.
     */
    @Test
    void savesAProfileWithNothingButTheAllergyStatus() throws SQLException {
        insertProfile();

        assertThat(countOf("SELECT COUNT(*) FROM app.eating_preferences "
                + "WHERE diet_pattern IS NULL AND height_cm IS NULL "
                + "AND current_weight_kg IS NULL AND activity_category IS NULL")).isEqualTo(1);
    }

    @Test
    void refusesASnackCountOutsideOneToThree() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute("UPDATE app.eating_preferences SET snack_count = 4 "
                + "WHERE profile_id = '" + PROFILE_ID + "'"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAZeroBudgetBecauseNullIsHowYouSayNotDeclared() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute("UPDATE app.eating_preferences SET daily_budget_vnd = 0 "
                + "WHERE profile_id = '" + PROFILE_ID + "'"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAnUnknownMealType() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.eating_preference_meal_types (profile_id, meal_type) "
                        + "VALUES ('" + PROFILE_ID + "', 'BRUNCH')"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void refusesAHardExclusionPointingAtAnIngredientThatDoesNotExist() throws SQLException {
        insertProfile();

        assertThatThrownBy(() -> execute(
                "INSERT INTO app.eating_preference_hard_exclusions (profile_id, ingredient_id) "
                        + "VALUES ('" + PROFILE_ID + "', 'ing.khong-ton-tai')"))
                .isInstanceOf(SQLException.class);
    }

    // -------------------------------------------------------------------------------------

    private void insertProfile() throws SQLException {
        execute("INSERT INTO app.eating_preferences "
                + "(profile_id, allergy_status, profile_version, created_at, updated_at) "
                + "VALUES ('" + PROFILE_ID + "', 'UNKNOWN', 0, "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private String preference(String recipeId, String type) {
        return "INSERT INTO app.user_recipe_preferences "
                + "(user_id, recipe_id, preference_type, created_at, updated_at) "
                + "VALUES ('" + USER_ID + "', '" + recipeId + "', '" + type + "', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }

    /** Bản rút gọn của các bảng cha ở V1, V4 và V57 — chỉ đủ cột để khóa ngoại gắn được. */
    private void createParentTables() throws SQLException {
        execute("CREATE TABLE app.users (id VARCHAR(36) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.pregnancies (id VARCHAR(36) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.allergens (id VARCHAR(64) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.ingredients (id VARCHAR(64) NOT NULL PRIMARY KEY)");
        execute("CREATE TABLE app.recipes (id VARCHAR(64) NOT NULL PRIMARY KEY)");
    }

    private void seedParentRows() throws SQLException {
        execute("INSERT INTO app.users (id) VALUES ('" + USER_ID + "')");
        execute("INSERT INTO app.pregnancies (id) VALUES ('" + PROFILE_ID + "')");
        execute("INSERT INTO app.allergens (id) VALUES ('alg.x')");
        execute("INSERT INTO app.ingredients (id) VALUES ('ing.x')");
        execute("INSERT INTO app.recipes (id) VALUES ('rec.x')");
    }

    private List<String> columnsOf(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = db.createStatement();
             var rows = statement.executeQuery(
                     "SELECT LOWER(column_name) FROM information_schema.columns "
                             + "WHERE LOWER(table_schema) = 'app' "
                             + "AND LOWER(table_name) = '" + table + "'")) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        return columns;
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
