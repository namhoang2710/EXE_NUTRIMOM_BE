package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeLineSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.NutritionSnapshots.ScaledIngredient;

/**
 * Gộp nguyên liệu của các mục thực đơn thành những dòng đi chợ.
 *
 * <h2>Khóa gộp là cặp (nguyên liệu, trạng thái chế biến) — không rộng hơn</h2>
 *
 * <p>Ba điều cấm của nhánh 7 đều là hệ quả của đúng một quyết định này:</p>
 * <ol>
 *   <li><strong>Không cộng 100 g gạo sống với 100 g cơm chín.</strong> Khác {@link FoodState} là
 *       khác dòng, kể cả khi là cùng một mã nguyên liệu (TC10). "200 g" ở đây là một con số không
 *       mua được ở bất kỳ quầy nào.</li>
 *   <li><strong>Không tự đổi thìa sang gram.</strong> Lớp này không quy đổi gì cả: lượng vào đã là
 *       gram vì công thức ghi gram. Quy đổi đơn vị ở đầu vào của người dùng đi qua
 *       {@link MassUnits}, nơi danh sách đơn vị là đóng và rất ngắn.</li>
 *   <li><strong>Không coi mọi loại cá là giống nhau.</strong> Khóa gộp là <em>mã</em> nguyên liệu,
 *       không phải tên, không phải nhóm thực phẩm, không phải "thẻ nguyên liệu chính". Cá hồi và
 *       cá cơm không bao giờ chạm nhau dù cùng {@link FoodGroup#MEAT_FISH_EGG}.</li>
 * </ol>
 *
 * <h2>Vì sao giữ lại từng lần dùng</h2>
 *
 * <p>Mỗi dòng mang theo danh sách {@link Usage} — món nào dùng bao nhiêu. Hai việc cần đúng nó:
 * màn hình phải hiện "món sử dụng" bên cạnh lượng cần, và "bỏ chọn món không cần mua" chỉ là lọc
 * bớt các lần dùng rồi cộng lại phần còn lại. Nếu chỉ giữ con số tổng thì bỏ chọn một món sẽ phải
 * tính lại từ đầu bằng một đường thứ hai, và hai đường cộng số thì sớm muộn lệch nhau.</p>
 *
 * <h2>Cùng một tỉ lệ với bộ tính dinh dưỡng</h2>
 *
 * <p>Lượng cho mỗi lần dùng lấy từ {@link NutritionCalculator#ingredientsFor} chứ không nhân chia
 * lại ở đây: nguyên liệu và dinh dưỡng của cùng một mục phải cùng một tỉ lệ (bất biến 4, TC08).
 * Một phép chia thứ hai đặt ở đây là một nguồn sự thật thứ hai cho câu hỏi "một phần là bao
 * nhiêu".</p>
 */
@Service
public class ShoppingAggregator {

    private final NutritionCalculator calculator;

    public ShoppingAggregator(NutritionCalculator calculator) {
        this.calculator = calculator;
    }

    /**
     * Một mục kế hoạch, ở dạng lớp này cần: ảnh chụp công thức và số phần đã chọn.
     *
     * @param itemId mã mục kế hoạch, để "bỏ chọn món không cần mua" trỏ được vào đúng lần dùng
     */
    public record PlannedPortion(String itemId, RecipeSnapshot recipe, BigDecimal servings) {
    }

    /** Một lần dùng nguyên liệu: món nào, mục nào, bao nhiêu gram. */
    public record Usage(String itemId, String recipeId, String recipeName, BigDecimal quantityGram) {
    }

    /**
     * Một dòng đi chợ trước khi gặp trạng thái của người dùng.
     *
     * <p>Thuần dẫn xuất từ thực đơn: cùng một tập mục kế hoạch luôn cho ra cùng một tập dòng, nên
     * đồng bộ lại danh sách sau khi đổi món là một phép so khớp chứ không phải một phép đoán.</p>
     */
    public record AggregatedLine(String ingredientId, String name, FoodState foodState,
                                 FoodGroup foodGroup, List<Usage> usages) {

        public AggregatedLine {
            usages = usages == null ? List.of() : List.copyOf(usages);
        }

        /** Tổng lượng cần, gram. */
        public BigDecimal quantityGram() {
            return usages.stream()
                    .map(Usage::quantityGram)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Chính dòng này nhưng chỉ còn những lần dùng được giữ lại; rỗng khi không còn lần nào. */
        public AggregatedLine retainUsages(List<Usage> kept) {
            return new AggregatedLine(ingredientId, name, foodState, foodGroup, kept);
        }
    }

    /**
     * Gộp các mục thành dòng đi chợ, xếp theo nhóm hàng rồi theo tên.
     *
     * <p>Thứ tự nhóm là thứ tự khai báo của {@link FoodGroup} — rau quả, thịt cá trứng, ngũ cốc,
     * rồi các nhóm còn lại — tức là thứ tự đi vòng quanh chợ, không phải thứ tự chữ cái.</p>
     */
    public List<AggregatedLine> aggregate(List<PlannedPortion> portions) {
        Map<LineKey, List<Usage>> usagesByLine = new LinkedHashMap<>();
        Map<LineKey, AggregatedLine> shape = new LinkedHashMap<>();

        for (PlannedPortion portion : portions) {
            RecipeSnapshot recipe = portion.recipe();
            Map<LineKey, FoodGroup> groups = groupsOf(recipe);
            for (ScaledIngredient scaled : calculator.ingredientsFor(recipe, portion.servings())) {
                LineKey key = new LineKey(scaled.ingredientId(), scaled.preparationState());
                shape.computeIfAbsent(key, missing -> new AggregatedLine(scaled.ingredientId(),
                        scaled.canonicalName(), scaled.preparationState(),
                        groups.getOrDefault(key, FoodGroup.OTHER), List.of()));
                usagesByLine.computeIfAbsent(key, missing -> new ArrayList<>())
                        .add(new Usage(portion.itemId(), recipe.recipeId(), recipe.name(),
                                scaled.quantityGram()));
            }
        }

        List<AggregatedLine> lines = new ArrayList<>();
        for (var entry : shape.entrySet()) {
            lines.add(entry.getValue().retainUsages(usagesByLine.get(entry.getKey())));
        }
        lines.sort(ordering());
        return List.copyOf(lines);
    }

    /** Thứ tự hiển thị: nhóm hàng theo thứ tự đi chợ, rồi tên, rồi trạng thái chế biến. */
    public static Comparator<AggregatedLine> ordering() {
        return Comparator.comparingInt((AggregatedLine line) -> line.foodGroup().ordinal())
                .thenComparing(AggregatedLine::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(line -> line.foodState().name());
    }

    /**
     * Nhóm thực phẩm của từng dòng công thức, đọc từ chính ảnh chụp.
     *
     * <p>Lấy từ snapshot chứ không tra danh mục hiện tại: dòng mua sắm phải mô tả đúng thứ mà kế
     * hoạch đã chọn, và ảnh chụp là thứ kế hoạch đã chọn.</p>
     */
    private Map<LineKey, FoodGroup> groupsOf(RecipeSnapshot recipe) {
        Map<LineKey, FoodGroup> groups = new LinkedHashMap<>();
        for (RecipeLineSnapshot line : recipe.lines()) {
            groups.putIfAbsent(
                    new LineKey(line.ingredient().ingredientId(), line.preparationState()),
                    line.ingredient().foodGroup());
        }
        return groups;
    }

    /** Danh tính của một dòng. Hai trường, không phải một — đó là toàn bộ TC10. */
    private record LineKey(String ingredientId, FoodState foodState) {
    }
}
