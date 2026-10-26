package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.nutrimom.nutrition.service.NutritionSeedData.Ingredient;
import vn.nutrimom.nutrition.service.NutritionSeedData.IngredientsFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.Recipe;
import vn.nutrimom.nutrition.service.NutritionSeedData.RecipesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.RulesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.SourcesFile;
import vn.nutrimom.nutrition.service.NutritionSeedValidator;

/**
 * Seed thật trong {@code src/main/resources/nutrition}, đọc thẳng từ file.
 *
 * <p>Các integration test chạy trên bộ 10 món đóng băng ở {@code src/test/resources/nutrition}
 * (classpath test đứng trước classpath main), vì chúng kiểm từng món cụ thể. Test này giữ phần còn
 * lại: seed thật vẫn qua validator, và không món hay nguyên liệu nào của bộ fixture bị đổi mã —
 * thực đơn và nhật ký của người dùng thật đang trỏ tới các mã đó.</p>
 */
class ProductionNutritionSeedTest {

    private static final Path MAIN = Path.of("src/main/resources/nutrition");
    private static final Path FIXTURE = Path.of("src/test/resources/nutrition");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void theProductionSeedPassesValidation() throws IOException {
        var problems = NutritionSeedValidator.validate(
                read(MAIN, "nutrientSources.json", SourcesFile.class),
                read(MAIN, "nutritionRules.json", RulesFile.class),
                read(MAIN, "ingredients.json", IngredientsFile.class),
                read(MAIN, "recipes.json", RecipesFile.class));

        assertThat(problems).isEmpty();
    }

    @Test
    void theProductionSeedKeepsEveryFixtureIdAndBumpsTheVersion() throws IOException {
        Set<String> recipeIds = recipeIds(MAIN);
        Set<String> ingredientIds = ingredientIds(MAIN);

        assertThat(recipeIds).hasSizeGreaterThanOrEqualTo(60).containsAll(recipeIds(FIXTURE));
        assertThat(ingredientIds).containsAll(ingredientIds(FIXTURE));
        assertThat(read(MAIN, "nutritionRules.json", RulesFile.class).rulesVersion())
                .isNotEqualTo(read(FIXTURE, "nutritionRules.json", RulesFile.class).rulesVersion());
    }

    @Test
    void recipeImagesAreObjectKeysUnderTheRecipesPrefix() throws IOException {
        for (Recipe recipe : read(MAIN, "recipes.json", RecipesFile.class).recipes()) {
            if (recipe.imageKey() != null) {
                assertThat(recipe.imageKey()).as(recipe.id()).matches("recipes/[a-z0-9-]+\\.jpg");
            }
        }
    }

    private static Set<String> recipeIds(Path dir) throws IOException {
        return read(dir, "recipes.json", RecipesFile.class).recipes().stream()
                .map(Recipe::id).collect(Collectors.toSet());
    }

    private static Set<String> ingredientIds(Path dir) throws IOException {
        return read(dir, "ingredients.json", IngredientsFile.class).ingredients().stream()
                .map(Ingredient::id).collect(Collectors.toSet());
    }

    private static <T> T read(Path dir, String file, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readString(dir.resolve(file)), type);
    }
}
