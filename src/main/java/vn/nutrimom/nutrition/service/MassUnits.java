package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Những đơn vị <strong>thực sự quy đổi được</strong> sang gram, và không có gì hơn.
 *
 * <p>Đây là một danh sách đóng rất ngắn, và sự ngắn đó là cố ý. "Thìa", "lít", "quả", "bó", "hộp"
 * đều <em>không</em> nằm ở đây vì quy chúng sang gram đòi một mật độ hoặc một khối lượng trung bình
 * mà danh mục chưa có — và một con số đoán trong danh sách đi chợ không hề trông giống một con số
 * đoán: nó trông hệt như một con số đã biết.</p>
 *
 * <p>Nơi gặp đơn vị không quy đổi được thì <strong>nói ra</strong> (hỏi người dùng nhập theo gram,
 * hoặc hiển thị "chưa có giá") chứ không tự chọn một hệ số. Cả danh sách mua sắm (nhánh 7) lẫn ước
 * tính chi phí món (nhánh 1) dùng chung đúng bảng này, nên không có hai định nghĩa "quy đổi được"
 * lệch nhau giữa hai màn hình.</p>
 */
public final class MassUnits {

    /** Đơn vị chuẩn của mọi lượng nguyên liệu: công thức ghi gram, nên danh sách cũng gram. */
    public static final String GRAM = "g";

    private static final MathContext MATH = new MathContext(16, RoundingMode.HALF_UP);

    private static final Map<String, BigDecimal> GRAMS_PER_UNIT = new LinkedHashMap<>();

    static {
        GRAMS_PER_UNIT.put("g", BigDecimal.ONE);
        GRAMS_PER_UNIT.put("gram", BigDecimal.ONE);
        GRAMS_PER_UNIT.put("kg", new BigDecimal("1000"));
    }

    private MassUnits() {
    }

    /** Đơn vị nào quy đổi được — công khai để tài liệu, thông báo lỗi và test không chép lại danh sách. */
    public static Set<String> convertible() {
        return Set.copyOf(GRAMS_PER_UNIT.keySet());
    }

    public static boolean isConvertible(String unit) {
        return GRAMS_PER_UNIT.containsKey(normalize(unit));
    }

    /** Số gram của một đơn vị; rỗng khi đơn vị <strong>chưa</strong> quy đổi được. */
    public static Optional<BigDecimal> gramsPerUnit(String unit) {
        return Optional.ofNullable(GRAMS_PER_UNIT.get(normalize(unit)));
    }

    /**
     * Quy một lượng sang gram; rỗng khi đơn vị chưa quy đổi được.
     *
     * <p>Rỗng là một câu trả lời đầy đủ, không phải một lỗi tạm: nó có nghĩa "không ai biết chỗ này
     * là bao nhiêu gram", và nơi gọi phải nói điều đó ra thay vì thay bằng 0.</p>
     */
    public static Optional<BigDecimal> toGram(BigDecimal quantity, String unit) {
        if (quantity == null) {
            return Optional.empty();
        }
        return gramsPerUnit(unit).map(quantity::multiply);
    }

    /** Quy một lượng gram sang {@code unit}; rỗng khi đơn vị chưa quy đổi được. */
    public static Optional<BigDecimal> fromGram(BigDecimal gram, String unit) {
        if (gram == null) {
            return Optional.empty();
        }
        return gramsPerUnit(unit).map(grams -> gram.divide(grams, MATH));
    }

    private static String normalize(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase();
    }
}
