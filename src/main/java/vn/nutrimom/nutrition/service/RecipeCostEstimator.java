package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RecipeEntity;

/**
 * Chi phí <strong>ước tính</strong> của một khẩu phần, khi và chỉ khi quy đổi được.
 *
 * <p>Nhánh 1 cố ý không trả tổng chi phí của món vì "quy đổi đơn vị (kg, lít, quả) sang lượng gram
 * chưa được định nghĩa". Lớp này <strong>không phá luật đó</strong>: nó chỉ quy đổi những đơn vị
 * thực sự là đơn vị khối lượng, và <strong>bỏ cuộc cả món</strong> ngay khi gặp một nguyên liệu
 * chưa có giá hoặc có giá theo đơn vị không quy đổi được ("lít", "quả").</p>
 *
 * <p>Bỏ cuộc cả món chứ không bỏ qua dòng đó: cộng tổng trên một nửa số nguyên liệu cho ra một con
 * số thấp hơn thực tế mà nhìn vẫn như số đầy đủ, rồi điểm B sẽ khen một món đắt là rẻ. Không có
 * giá thì điểm B không hoạt động — <strong>không phải</strong> B = 0.</p>
 *
 * <p>Đoán một mật độ chung để quy "lít" sang gram chính là lỗi đặc tả cấm, nên ở đây không có
 * phép đoán nào.</p>
 */
@Service
public class RecipeCostEstimator {

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal GRAMS_PER_KG = new BigDecimal("1000");

    /** Đơn vị khối lượng quy đổi được sang gram, kèm số gram của một đơn vị. */
    private static final Map<String, BigDecimal> MASS_UNITS = Map.of(
            "kg", GRAMS_PER_KG,
            "g", BigDecimal.ONE,
            "gram", BigDecimal.ONE);

    /**
     * Chi phí một khẩu phần, hoặc rỗng khi món có bất kỳ nguyên liệu nào chưa quy đổi được giá.
     *
     * @param pricesByIngredient giá theo mã nguyên liệu; mã vắng mặt nghĩa là <em>chưa có giá</em>
     */
    public Optional<BigDecimal> perServing(RecipeEntity recipe,
                                           Map<String, PriceEstimateEntity> pricesByIngredient) {
        if (recipe.getServingsYield() == null || recipe.getServingsYield().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (var line : recipe.getIngredients()) {
            PriceEstimateEntity price = pricesByIngredient.get(line.getIngredientId());
            if (price == null || price.getPricePerBaseUnitVnd() == null) {
                return Optional.empty();
            }
            BigDecimal gramsPerUnit = MASS_UNITS.get(normalize(price.getBaseUnit()));
            if (gramsPerUnit == null) {
                return Optional.empty();
            }
            BigDecimal units = line.getQuantityGram().divide(gramsPerUnit, MATH);
            total = total.add(units.multiply(price.getPricePerBaseUnitVnd()));
        }
        return Optional.of(total.divide(recipe.getServingsYield(), MATH)
                .setScale(2, RoundingMode.HALF_UP));
    }

    /** Đơn vị nào quy đổi được — công khai để tài liệu và test không phải chép lại danh sách. */
    public Set<String> convertibleUnits() {
        return MASS_UNITS.keySet();
    }

    private String normalize(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase();
    }
}
