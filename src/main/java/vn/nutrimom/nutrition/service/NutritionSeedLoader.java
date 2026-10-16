package vn.nutrimom.nutrition.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.nutrimom.nutrition.domain.AllergenEntity;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.IngredientNutrientValue;
import vn.nutrimom.nutrition.domain.MealTemplateEntity;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.domain.NutrientEntity;
import vn.nutrimom.nutrition.domain.NutritionSeedStateEntity;
import vn.nutrimom.nutrition.domain.NutritionSourceEntity;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RecipeEntity;
import vn.nutrimom.nutrition.domain.RecipeIngredientValue;
import vn.nutrimom.nutrition.domain.RecipeRole;
import vn.nutrimom.nutrition.repository.AllergenRepository;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealTemplateRepository;
import vn.nutrimom.nutrition.repository.NutrientRepository;
import vn.nutrimom.nutrition.repository.NutritionSeedStateRepository;
import vn.nutrimom.nutrition.repository.NutritionSourceRepository;
import vn.nutrimom.nutrition.repository.PriceEstimateRepository;
import vn.nutrimom.nutrition.repository.RecipeRepository;
import vn.nutrimom.nutrition.service.NutritionSeedData.Ingredient;
import vn.nutrimom.nutrition.service.NutritionSeedData.IngredientsFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.Recipe;
import vn.nutrimom.nutrition.service.NutritionSeedData.RecipesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.RulesFile;
import vn.nutrimom.nutrition.service.NutritionSeedData.SourcesFile;

/**
 * Nạp danh mục món từ {@code src/main/resources/nutrition/*.json} vào database lúc khởi động.
 *
 * <p>Thứ tự bắt buộc là <strong>đọc, kiểm tra, rồi mới ghi</strong>: validator chạy trước khi
 * chạm vào database, và tập seed sai thì ứng dụng <strong>không khởi động được</strong>. Để seed
 * hỏng đi vào DB rồi mới phát hiện thì danh mục món đã lẫn dữ liệu bẩn, trong khi mọi nhánh sau
 * đều dựng trên nó.</p>
 *
 * <p>Nạp lại chỉ xảy ra khi {@code rulesVersion} trong {@code nutritionRules.json} khác với giá trị
 * đã ghi ở {@code app.nutrition_seed_state}.</p>
 *
 * <p><strong>Cập nhật chứ không xoá sạch.</strong> Loader ghi đè các dòng có trong seed và
 * <em>không</em> xoá dòng đã biến mất khỏi seed — chỉ ghi log cảnh báo. Từ nhánh 5 trở đi,
 * {@code meal_plan_items} và {@code diary_entries} tham chiếu tới {@code recipes}; xoá sạch rồi nạp
 * lại sẽ làm hỏng lịch sử đã ăn của người dùng, đúng thứ mà BR17 cấm. Dọn món cũ là việc có chủ ý,
 * phải làm bằng migration riêng khi biết rõ ảnh hưởng.</p>
 */
@Component
public class NutritionSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NutritionSeedLoader.class);

    private static final String SOURCES_PATH = "nutrition/nutrientSources.json";
    private static final String RULES_PATH = "nutrition/nutritionRules.json";
    private static final String INGREDIENTS_PATH = "nutrition/ingredients.json";
    private static final String RECIPES_PATH = "nutrition/recipes.json";

    /**
     * Mapper RIÊNG, cố ý không dùng bean dùng chung.
     *
     * <p>ObjectMapper của ứng dụng chạy {@code property-naming-strategy: SNAKE_CASE} cho hợp đồng
     * API. File seed thì do người nhập liệu viết tay và dùng camelCase, nên nếu mượn bean chung thì
     * {@code canonicalName} sẽ không khớp và mọi trường im lặng thành null. Tách mapper ở đây giữ
     * hai thứ độc lập: đổi quy ước JSON của API không làm hỏng seed, và ngược lại.</p>
     */
    private static final ObjectMapper SEED_MAPPER = JsonMapper.builder().build();

    private final boolean enabled;
    private final NutritionSourceRepository sources;
    private final NutrientRepository nutrients;
    private final AllergenRepository allergens;
    private final IngredientRepository ingredients;
    private final PriceEstimateRepository prices;
    private final RecipeRepository recipes;
    private final MealTemplateRepository templates;
    private final NutritionSeedStateRepository state;

    public NutritionSeedLoader(@Value("${app.nutrition.seed-enabled:true}") boolean enabled,
                               NutritionSourceRepository sources, NutrientRepository nutrients,
                               AllergenRepository allergens, IngredientRepository ingredients,
                               PriceEstimateRepository prices, RecipeRepository recipes,
                               MealTemplateRepository templates, NutritionSeedStateRepository state) {
        this.enabled = enabled;
        this.sources = sources;
        this.nutrients = nutrients;
        this.allergens = allergens;
        this.ingredients = ingredients;
        this.prices = prices;
        this.recipes = recipes;
        this.templates = templates;
        this.state = state;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Bỏ qua nạp seed dinh dưỡng: app.nutrition.seed-enabled=false");
            return;
        }
        load();
    }

    /** Tách khỏi {@link #run} để test gọi thẳng được mà không cần dựng ApplicationArguments. */
    @Transactional
    public void load() {
        SourcesFile sourcesFile = read(SOURCES_PATH, SourcesFile.class);
        RulesFile rulesFile = read(RULES_PATH, RulesFile.class);
        IngredientsFile ingredientsFile = read(INGREDIENTS_PATH, IngredientsFile.class);
        RecipesFile recipesFile = read(RECIPES_PATH, RecipesFile.class);

        List<String> problems =
                NutritionSeedValidator.validate(sourcesFile, rulesFile, ingredientsFile, recipesFile);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Tập seed dinh dưỡng không hợp lệ, có "
                    + problems.size() + " vi phạm:\n  - " + String.join("\n  - ", problems));
        }

        String version = rulesFile.rulesVersion();
        var current = state.findById(NutritionSeedStateEntity.SINGLE_ROW_ID).orElse(null);
        if (current != null && version.equals(current.getRulesVersion())) {
            log.debug("Seed dinh dưỡng đã ở phiên bản {}, không nạp lại", version);
            return;
        }

        writeCatalogs(sourcesFile, ingredientsFile);
        writeIngredients(ingredientsFile);
        writeRecipes(recipesFile);
        writeTemplates(rulesFile);

        var row = current == null ? new NutritionSeedStateEntity() : current;
        row.setId(NutritionSeedStateEntity.SINGLE_ROW_ID);
        row.setRulesVersion(version);
        row.setAppliedAt(OffsetDateTime.now(ZoneOffset.UTC));
        state.save(row);

        log.info("Đã nạp seed dinh dưỡng {}: {} nguyên liệu, {} công thức",
                version, ingredientsFile.ingredients().size(), recipesFile.recipes().size());
        warnAboutRowsNotInSeed(ingredientsFile, recipesFile);
    }

    private void writeCatalogs(SourcesFile sourcesFile, IngredientsFile ingredientsFile) {
        sourcesFile.sources().forEach(source -> {
            var entity = new NutritionSourceEntity();
            entity.setId(source.id());
            entity.setName(source.name());
            entity.setRecordRef(source.recordRef());
            entity.setRetrievedOn(source.retrievedOn());
            sources.save(entity);
        });
        sourcesFile.nutrients().forEach(nutrient -> {
            var entity = new NutrientEntity();
            entity.setCode(nutrient.code());
            entity.setName(nutrient.name());
            entity.setUnit(nutrient.unit());
            nutrients.save(entity);
        });
        ingredientsFile.allergens().forEach(allergen -> {
            var entity = new AllergenEntity();
            entity.setId(allergen.id());
            entity.setName(allergen.name());
            allergens.save(entity);
        });
    }

    /**
     * Ghi nguyên liệu bằng <strong>hai lượt</strong>.
     *
     * <p>Lượt một ghi mọi nguyên liệu với tập thành phần con rỗng rồi flush; lượt hai mới gắn thành
     * phần con. Lý do: khoá ngoại {@code ingredient_components.child_ingredient_id} đòi nguyên liệu
     * con đã tồn tại, mà thứ tự trong file JSON không bảo đảm con đứng trước cha — nước mắm có thể
     * nằm trước cá cơm.</p>
     */
    private void writeIngredients(IngredientsFile file) {
        for (Ingredient source : file.ingredients()) {
            var entity = ingredients.findById(source.id()).orElseGet(IngredientEntity::new);
            entity.setId(source.id());
            entity.setCanonicalName(source.canonicalName());
            entity.setFoodState(source.foodState());
            entity.setFoodGroup(source.foodGroup());
            entity.setAllergenStatus(source.allergenStatus());
            entity.setSourceId(source.sourceId());
            entity.setAliases(new LinkedHashSet<>(orEmpty(source.aliases())));
            entity.setAllergenIds(new LinkedHashSet<>(orEmpty(source.allergenIds())));
            entity.setComponentIngredientIds(Set.of());
            entity.setNutrients(orEmpty(source.nutrients()).stream()
                    .map(value -> new IngredientNutrientValue(value.code(), value.amountPer100g(),
                            value.unit(), value.status(), value.sourceId()))
                    .toList());
            ingredients.save(entity);
        }
        ingredients.flush();

        for (Ingredient source : file.ingredients()) {
            List<String> components = orEmpty(source.components());
            if (components.isEmpty()) {
                continue;
            }
            var entity = ingredients.findById(source.id()).orElseThrow();
            entity.setComponentIngredientIds(new LinkedHashSet<>(components));
            ingredients.save(entity);
        }

        for (Ingredient source : file.ingredients()) {
            var price = source.price();
            if (price == null) {
                // Không có dòng giá là chuyện bình thường: màn mua sắm hiển thị "Chưa có giá" và tổng
                // chi phí mang nhãn chưa đầy đủ, chứ không coi là 0 đồng.
                continue;
            }
            var entity = prices.findById(source.id()).orElseGet(PriceEstimateEntity::new);
            entity.setIngredientId(source.id());
            entity.setPricePerBaseUnitVnd(price.pricePerBaseUnitVnd());
            entity.setBaseUnit(price.baseUnit());
            entity.setEstimatedAt(price.estimatedAt());
            entity.setSourceNote(price.sourceNote());
            prices.save(entity);
        }
    }

    private void writeRecipes(RecipesFile file) {
        for (Recipe source : file.recipes()) {
            var entity = recipes.findById(source.id()).orElseGet(RecipeEntity::new);
            entity.setId(source.id());
            entity.setName(source.name());
            entity.setVersion(source.version());
            entity.setRole(source.role());
            entity.setServingsYield(source.servingsYield());
            entity.setServingGram(source.servingGram());
            entity.setMinutes(source.minutes());
            entity.setSteps(source.steps());
            entity.setSafetyNotes(source.safetyNotes());
            entity.setImageKey(source.imageKey());
            entity.setMainIngredientTag(source.mainIngredientTag());
            entity.setMealTypes(source.mealTypes().isEmpty()
                    ? EnumSet.noneOf(MealType.class) : EnumSet.copyOf(source.mealTypes()));
            entity.setIngredients(source.ingredients().stream()
                    .map(line -> new RecipeIngredientValue(line.ingredientId(), line.quantityGram(),
                            line.preparationState()))
                    .toList());
            recipes.save(entity);
        }
    }

    private void writeTemplates(RulesFile file) {
        for (var source : file.mealTemplates()) {
            var entity = new MealTemplateEntity();
            entity.setMealType(source.mealType());
            entity.setMode(source.mode());
            entity.setRoleTargetShare(source.roleTargetShare());
            entity.setDefaultServingOptions(source.defaultServingOptions());
            entity.setRequiredRoles(source.requiredRoles().isEmpty()
                    ? EnumSet.noneOf(RecipeRole.class) : EnumSet.copyOf(source.requiredRoles()));
            templates.save(entity);
        }
    }

    /**
     * Cảnh báo về dòng còn trong database mà không còn trong seed.
     *
     * <p>Cố ý chỉ log chứ không xoá: từ nhánh 5 trở đi các bảng kế hoạch và nhật ký tham chiếu tới
     * {@code recipes}, xoá đi là viết lại lịch sử đã ăn của người dùng (BR17).</p>
     */
    private void warnAboutRowsNotInSeed(IngredientsFile ingredientsFile, RecipesFile recipesFile) {
        Set<String> seededRecipes = recipesFile.recipes().stream()
                .map(Recipe::id).collect(Collectors.toSet());
        List<String> orphanRecipes = recipes.findAll().stream()
                .map(RecipeEntity::getId)
                .filter(id -> !seededRecipes.contains(id))
                .toList();
        if (!orphanRecipes.isEmpty()) {
            log.warn("{} công thức còn trong database nhưng không còn trong seed, giữ nguyên để không "
                    + "làm hỏng lịch sử: {}", orphanRecipes.size(), orphanRecipes);
        }

        Set<String> seededIngredients = ingredientsFile.ingredients().stream()
                .map(Ingredient::id).collect(Collectors.toSet());
        List<String> orphanIngredients = ingredients.findAll().stream()
                .map(IngredientEntity::getId)
                .filter(id -> !seededIngredients.contains(id))
                .toList();
        if (!orphanIngredients.isEmpty()) {
            log.warn("{} nguyên liệu còn trong database nhưng không còn trong seed: {}",
                    orphanIngredients.size(), orphanIngredients);
        }
    }

    private <T> T read(String path, Class<T> type) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            T value = SEED_MAPPER.readValue(input, type);
            if (value == null) {
                throw new IllegalStateException("File seed rỗng: " + path);
            }
            return value;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Không đọc được file seed " + path, error);
        }
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}
