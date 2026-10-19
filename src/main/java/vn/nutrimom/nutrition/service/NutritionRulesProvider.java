package vn.nutrimom.nutrition.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.nutrimom.nutrition.domain.ActivityCategory;
import vn.nutrimom.nutrition.domain.TrimesterGroup;
import vn.nutrimom.nutrition.service.NutritionRules.EnergyEstimateRules;
import vn.nutrimom.nutrition.service.NutritionRules.RetentionFactor;
import vn.nutrimom.nutrition.service.NutritionRules.WeeklyFoodLimit;
import vn.nutrimom.nutrition.service.NutritionSeedData.MicronutrientTarget;
import vn.nutrimom.nutrition.service.NutritionSeedData.SourcesFile;

/**
 * Nạp {@link NutritionRules} từ classpath một lần lúc khởi động.
 *
 * <p>Cố ý <strong>đọc file chứ không đọc database</strong>: bộ tính dinh dưỡng phải chạy được
 * trong test thuần, và mọi phép tính của một lượt gọi phải dùng đúng một tập quy tắc nhất quán —
 * đọc từng dòng từ bảng trong vòng lặp vừa chậm vừa có thể lẫn hai phiên bản seed.</p>
 *
 * <p>Mapper riêng, không mượn bean chung, vì lý do giống {@link NutritionSeedLoader}: file seed
 * viết camelCase còn hợp đồng API là snake_case.</p>
 *
 * <p>Kiểm tra ngay lúc nạp và <strong>ném lỗi làm ứng dụng không khởi động được</strong> nếu thiếu
 * hệ số: phát hiện lúc khởi động rẻ hơn nhiều so với phát hiện khi một người dùng thật bấm "ước
 * tính năng lượng" và nhận về con số sai.</p>
 */
@Component
public class NutritionRulesProvider {

    private static final String RULES_PATH = "nutrition/nutritionRules.json";
    private static final String SOURCES_PATH = "nutrition/nutrientSources.json";

    private static final ObjectMapper SEED_MAPPER = JsonMapper.builder().build();

    private final NutritionRules rules;

    public NutritionRulesProvider() {
        this.rules = load();
    }

    public NutritionRules rules() {
        return rules;
    }

    /** Hình dạng phần {@code nutritionRules.json} mà nhánh 2 quan tâm. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RulesDocument(String rulesVersion,
                                 List<MicronutrientTarget> micronutrientTargets,
                                 List<RetentionFactor> retentionFactors,
                                 List<WeeklyFoodLimit> weeklyFoodLimits,
                                 EnergyEstimateRules energyEstimate) {
    }

    private static NutritionRules load() {
        RulesDocument document = read(RULES_PATH, RulesDocument.class);
        SourcesFile sources = read(SOURCES_PATH, SourcesFile.class);

        Map<String, String> units = new LinkedHashMap<>();
        for (var nutrient : orEmpty(sources.nutrients())) {
            units.put(nutrient.code(), nutrient.unit());
        }

        var loaded = new NutritionRules(
                document.rulesVersion(),
                Map.copyOf(units),
                List.copyOf(orEmpty(document.micronutrientTargets())),
                List.copyOf(orEmpty(document.retentionFactors())),
                List.copyOf(orEmpty(document.weeklyFoodLimits())),
                document.energyEstimate());
        check(loaded);
        return loaded;
    }

    private static void check(NutritionRules loaded) {
        List<String> problems = new ArrayList<>();
        if (loaded.rulesVersion() == null || loaded.rulesVersion().isBlank()) {
            problems.add("thiếu rulesVersion");
        }
        if (loaded.micronutrientTargets().isEmpty()) {
            problems.add("không có mục tiêu vi chất nào");
        }
        for (var target : loaded.micronutrientTargets()) {
            if (!loaded.nutrientUnits().containsKey(target.code())) {
                problems.add("mục tiêu vi chất " + target.code() + " không có trong danh mục chất");
            }
        }

        // Một dòng giới hạn tuần hỏng là một điều kiện an toàn im lặng không chạy: mã trùng thì
        // dòng sau che dòng trước, mức ≤ 0 thì mọi kế hoạch bị chặn, còn nhóm rỗng thì giới hạn
        // tồn tại trên giấy mà không đếm nguyên liệu nào. Cả ba phải nổ lúc khởi động.
        Set<String> limitCodes = new LinkedHashSet<>();
        for (var limit : loaded.weeklyFoodLimits()) {
            if (limit.code() == null || limit.code().isBlank()) {
                problems.add("có giới hạn theo tuần thiếu code");
            } else if (!limitCodes.add(limit.code())) {
                problems.add("giới hạn theo tuần trùng code " + limit.code());
            }
            if (limit.ingredientIds().isEmpty()) {
                problems.add("giới hạn theo tuần " + limit.code() + " không khai nguyên liệu nào");
            }
            if (limit.maxGramPerWeek() == null || limit.maxGramPerWeek().signum() <= 0) {
                problems.add("giới hạn theo tuần " + limit.code() + " có maxGramPerWeek ≤ 0");
            }
        }

        EnergyEstimateRules energy = loaded.energyEstimate();
        if (energy == null) {
            problems.add("thiếu khối energyEstimate");
        } else {
            // Thiếu một dòng hệ số thì nhóm vận động tương ứng sẽ không ước tính được năng lượng,
            // và người dùng chỉ thấy "chưa đủ dữ liệu" dù họ đã khai đủ.
            for (TrimesterGroup group : TrimesterGroup.values()) {
                for (ActivityCategory activity : ActivityCategory.values()) {
                    if (loaded.formulaFor(group, activity).isEmpty()) {
                        problems.add("thiếu hệ số EER cho " + group + "/" + activity);
                    }
                }
            }
            var adjustments = orEmpty(energy.bmiAdjustments());
            if (adjustments.isEmpty()
                    || adjustments.get(adjustments.size() - 1).maxBmiExclusive() != null) {
                problems.add("bmiAdjustments phải kết thúc bằng dòng maxBmiExclusive = null");
            }
            if (energy.trimester2StartWeek() <= 0) {
                problems.add("trimester2StartWeek phải lớn hơn 0");
            }
            if (energy.minAgeYears() <= 0 || energy.maxAgeYears() < energy.minAgeYears()) {
                problems.add("khoảng tuổi áp dụng không hợp lệ");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("nutritionRules.json không dùng được, có "
                    + problems.size() + " vi phạm:\n  - " + String.join("\n  - ", problems));
        }
    }

    private static <T> T read(String path, Class<T> type) {
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
        return values == null ? List.of() : values;
    }
}
