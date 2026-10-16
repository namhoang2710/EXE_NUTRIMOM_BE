package vn.nutrimom.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealTemplateRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;

/**
 * Danh mục món chạy thật: seed được nạp lúc dựng context, rồi hai endpoint công khai phục vụ nó.
 *
 * <p>Bật lại {@code app.nutrition.seed-enabled} vì profile test tắt mặc định — mọi integration test
 * khác không cần chờ nạp cả danh mục.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.nutrition.seed-enabled=true")
class RecipeCatalogIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired RecipeRepository recipes;
    @Autowired IngredientRepository ingredients;
    @Autowired MealTemplateRepository templates;

    @Test
    void theRealSeedFilesLoadAndPassValidation() {
        // Context dựng được đã là một nửa khẳng định: seed sai thì loader ném IllegalStateException
        // và ứng dụng không khởi động.
        assertThat(recipes.count()).isGreaterThanOrEqualTo(8);
        assertThat(ingredients.count()).isGreaterThanOrEqualTo(15);
        assertThat(templates.count()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @Transactional
    void componentsSurviveTheTwoPassWriteEvenWhenTheChildComesLaterInTheFile() {
        // Nước mắm đứng trước cá cơm trong ingredients.json; nếu ghi một lượt thì khoá ngoại nổ.
        var fishSauce = ingredients.findById("ing.nuoc-mam").orElseThrow();
        assertThat(fishSauce.getComponentIngredientIds()).contains("ing.ca-com");

        var shrimpBroth = ingredients.findById("ing.nuoc-dung-tom").orElseThrow();
        assertThat(shrimpBroth.getComponentIngredientIds()).contains("ing.tom-kho");
    }

    @Test
    @Transactional
    void missingNutrientDataStaysNullInsteadOfBecomingZero() {
        var fishSauce = ingredients.findById("ing.nuoc-mam").orElseThrow();

        var folate = fishSauce.getNutrients().stream()
                .filter(value -> value.getNutrientCode().equals("FOLATE"))
                .findFirst()
                .orElseThrow();

        assertThat(folate.getAmountPer100g()).isNull();
    }

    @Test
    @Transactional
    void unscreenedIngredientKeepsUnknownStatusWithNoAllergensListed() {
        var seasoning = ingredients.findById("ing.gia-vi-nem-san").orElseThrow();

        assertThat(seasoning.getAllergenStatus()).isEqualTo(AllergenStatus.UNKNOWN);
        assertThat(seasoning.getAllergenIds()).isEmpty();
    }

    @Test
    void listIsReadableWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].id").exists());
    }

    @Test
    void detailIsReadableWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/{id}", "rec.com-trang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.name").value("Cơm trắng"))
                .andExpect(jsonPath("$.data.ingredients[0].ingredient_id").value("ing.gao-te"))
                .andExpect(jsonPath("$.data.steps").isNotEmpty());
    }

    @Test
    void detailReportsWhereDataIsStillMissingRatherThanFillingZeros() throws Exception {
        // Gà kho gừng dùng nước mắm, mà nước mắm chưa có số liệu folate.
        mockMvc.perform(get("/api/v1/recipes/{id}", "rec.ga-kho-gung"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data_state.nutrient_codes_missing_data")
                        .value(org.hamcrest.Matchers.hasItem("FOLATE")));
    }

    @Test
    void detailNamesTheUnscreenedIngredientSoTheUiCanExplainTheExclusion() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/{id}", "rec.canh-rau-nem-san"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data_state.ingredients_with_unknown_allergen")
                        .value(org.hamcrest.Matchers.hasItem("ing.gia-vi-nem-san")));
    }

    @Test
    void filtersNarrowTheListByMealTypeRoleAndCookingTime() throws Exception {
        mockMvc.perform(get("/api/v1/recipes").param("mealType", MealType.SNACK.name()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].id")
                        .value(org.hamcrest.Matchers.hasItem("rec.sua-chuoi")));

        mockMvc.perform(get("/api/v1/recipes").param("maxMinutes", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].minutes")
                        .value(org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.lessThanOrEqualTo(10))));
    }

    @Test
    void cursorPagingWalksTheWholeCatalogWithoutRepeating() throws Exception {
        var first = mockMvc.perform(get("/api/v1/recipes").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has_more").value(true))
                .andExpect(jsonPath("$.data.next_cursor").isNotEmpty())
                .andReturn();

        String cursor = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.next_cursor");
        String firstLastId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.items[2].id");

        assertThat(cursor).isEqualTo(firstLastId);

        mockMvc.perform(get("/api/v1/recipes").param("limit", "3").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id")
                        .value(org.hamcrest.Matchers.greaterThan(cursor)));
    }

    @Test
    void unknownRecipeIsANotFoundRatherThanAnEmptyBody() throws Exception {
        mockMvc.perform(get("/api/v1/recipes/{id}", "rec.khong-ton-tai"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
