package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.nutrition.domain.MealType;
import vn.nutrimom.nutrition.dto.MealPlanDtos.LockedItemRequest;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PlanConstraints;
import vn.nutrimom.nutrition.dto.MealPlanDtos.PreviewRequest;

/**
 * Dấu vân tay của một {@link PreviewRequest} — thứ để biết lần gửi lại có thật là
 * <em>cùng một yêu cầu</em> hay không (_CONTRACT.md mục 6).
 *
 * <h2>Vì sao cần nó</h2>
 *
 * <p>Riêng {@code request_id} không đủ. Một client sửa yêu cầu rồi gửi lại bằng đúng
 * {@code request_id} cũ — thêm {@code locked_items} chẳng hạn — sẽ nhận lại bản xem trước cũ mà
 * không có dấu hiệu nào cho biết yêu cầu mới chưa từng được chạy. Có dấu vân tay thì trường hợp đó
 * thành {@code IDEMPOTENCY_CONFLICT} (409) với câu "hãy đổi request_id", còn lần gửi lại thật sự
 * vì mạng chậm vẫn trả đúng bản cũ.</p>
 *
 * <h2>Vì sao không thêm cột, không thêm migration</h2>
 *
 * <p>Dấu vân tay nằm <strong>trong chính cột {@code payload}</strong> của
 * {@code app.meal_plan_previews}, dưới khóa {@value #PAYLOAD_FIELD}; không có migration nào trong
 * thay đổi này. Lý do là rủi ro, không phải lười: database Supabase dùng chung chạy trước repo
 * (_CONTRACT.md mục 2) và tới lúc viết dòng này V63–V64 vẫn chưa được áp lên nó, nên không hỏi
 * được {@code flyway_schema_history} thì mọi số mới đều là số đoán. Đoán trúng số người khác đang
 * giữ thì {@code out-of-order} đang tắt sẽ làm Flyway <strong>im lặng bỏ qua</strong> migration,
 * và triệu chứng duy nhất là ứng dụng không khởi động được vì {@code ddl-auto: validate} thấy
 * thiếu cột. Cột {@code payload} thì đã là JSONB, đã thuộc về đúng dòng này, và module tự ghi tự
 * đọc nó — thêm một khóa riêng vào đó không cần ai đồng bộ với ai.</p>
 *
 * <p>Giá phải trả: những dòng ghi <em>trước</em> thay đổi này không có khóa đó, nên lần gửi lại
 * trúng chúng giữ nguyên hành vi cũ (trả bản cũ, không đối chiếu). Bản xem trước sống 30 phút, nên
 * khoảng mù đó tự hết sau nửa giờ kể từ lúc deploy.</p>
 *
 * <h2>Lấy những trường nào</h2>
 *
 * <p>Đúng những gì <strong>quyết định nội dung</strong> thực đơn sinh ra: ngày bắt đầu, số ngày,
 * các bữa, số bữa phụ, giới hạn tự đặt, món khóa và seed. <strong>Cố ý bỏ</strong>
 * {@code request_id} (nó là khóa, không phải nội dung) và {@code profile_version} (đã được
 * {@code requireMatchingProfileVersion} kiểm riêng trước đó, và kiểm bằng giá trị hiện tại của hồ
 * sơ chứ không bằng giá trị lần trước).</p>
 *
 * <p>Giá trị được chuẩn hóa trước khi băm — mặc định được điền, tập bữa và danh sách món khóa được
 * sắp xếp, {@code servings} bỏ số 0 thừa — để hai body khác nhau về cách viết mà giống nhau về
 * nghĩa không bị báo xung đột oan.</p>
 */
record PreviewFingerprint(LocalDate startDate, int dayCount, List<String> mealTypes,
                          short snackCount, Long dailyBudgetVnd, Integer maxMinutesPerMeal,
                          boolean strictBudget, boolean strictTime, List<LockedKey> lockedItems,
                          long seed) {

    /** Khóa giữ dấu vân tay trong {@code payload}; gạch dưới để không lẫn với trường của API. */
    static final String PAYLOAD_FIELD = "_request_fingerprint";

    /** Một món khóa, rút về đúng phần người dùng nêu ra. */
    record LockedKey(String mealType, short sequence, String recipeId, String servings) {
    }

    /**
     * Băm SHA-256 của dạng chuẩn hóa, viết bằng hex.
     *
     * <p>Qua {@link ObjectMapper} chứ không nối chuỗi tay: JSON tự thoát ký tự, nên một
     * {@code recipe_id} chứa đúng ký tự ngăn cách không thể giả làm một yêu cầu khác.</p>
     */
    static String of(PreviewRequest request, ObjectMapper json) {
        return sha256Hex(json.writeValueAsString(canonical(request)));
    }

    private static PreviewFingerprint canonical(PreviewRequest request) {
        PlanConstraints constraints = request.constraintsOrEmpty();
        List<String> mealTypes = request.mealTypes() == null ? List.of()
                : request.mealTypes().stream().map(MealType::name).sorted().toList();
        return new PreviewFingerprint(request.startDate(), request.dayCountOrDefault(), mealTypes,
                request.snackCountOrDefault(), constraints.dailyBudgetVnd(),
                constraints.maxMinutesPerMeal(), constraints.strictBudgetEnabled(),
                constraints.strictTimeEnabled(), lockedKeys(request), request.seedOrDefault());
    }

    /**
     * Món khóa sắp theo thứ tự cố định: cùng một tập món, gửi theo thứ tự nào cũng là một yêu cầu.
     */
    private static List<LockedKey> lockedKeys(PreviewRequest request) {
        return request.lockedItemsOrEmpty().stream()
                .map(PreviewFingerprint::lockedKey)
                .sorted(Comparator
                        .comparing(LockedKey::mealType, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(LockedKey::sequence)
                        .thenComparing(LockedKey::recipeId, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(LockedKey::servings, Comparator.nullsFirst(String::compareTo)))
                .toList();
    }

    private static LockedKey lockedKey(LockedItemRequest item) {
        // 1 và 1.00 là cùng một khẩu phần: so sánh theo giá trị, không theo cách viết.
        BigDecimal servings = item.servings();
        return new LockedKey(item.mealType() == null ? null : item.mealType().name(),
                item.sequenceOrDefault(), item.recipeId(),
                servings == null ? null : servings.stripTrailingZeros().toPlainString());
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 phải có trong mọi JVM", impossible);
        }
    }
}
