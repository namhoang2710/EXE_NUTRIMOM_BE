package vn.nutrimom.nutrition.domain;

/**
 * Lý do có thể hiển thị cho một món, hoặc cho cả một lượt gợi ý.
 *
 * <p><strong>BR15: giải thích phải lấy từ dữ liệu thật.</strong> Danh sách đóng này chính là cách
 * ép điều đó: mỗi lý do chỉ phát ra được khi nơi gọi cầm trong tay đúng mẩu dữ liệu điền vào chỗ
 * trống của mẫu câu — tên nguyên liệu gây dị ứng, số phút nấu, tên chế độ ăn. Không có chỗ nào
 * sinh câu tự do, nên không có cách nào vô tình viết ra một lời khẳng định không đối chiếu được.
 * Cũng vì vậy nhánh này <strong>không cần dịch vụ AI bên ngoài</strong>.</p>
 *
 * <p><strong>Không khẳng định điều trị.</strong> Các lý do thuộc nhóm dinh dưỡng chỉ nói món khớp
 * tới đâu với <em>phần tham chiếu kỹ thuật</em> của bữa — chúng không nói món hay ngày đã đủ chất,
 * và không bao giờ được viết lại thành lời khuyên y khoa. Điểm thuật toán giữ ở backend, không
 * biến thành "điểm sức khỏe" trên giao diện.</p>
 *
 * <p>{@link Kind} tồn tại để một mã chặn không bao giờ bị đem ra làm lời khen. Nơi hiển thị lọc
 * theo {@link #kind()} chứ không tự nhớ mã nào thuộc nhóm nào.</p>
 */
public enum RecommendationReason {

    // --- Vì sao món bị loại -------------------------------------------------------------------

    /** Món chứa thẳng một thành phần người dùng đã khai dị ứng. */
    ALLERGEN_MATCH(Kind.BLOCKING, "Có %s — thành phần bạn đã khai dị ứng"),

    /**
     * Thành phần gây dị ứng nằm trong <strong>nguyên liệu con</strong>.
     *
     * <p>BR02, TC01: nước dùng nấu bằng tôm khô phải chặn món với người dị ứng tôm, dù danh sách
     * nguyên liệu của món không hề viết chữ "tôm". Mã riêng chứ không dùng chung
     * {@link #ALLERGEN_MATCH} để người dùng hiểu vì sao một món trông vô hại lại bị loại.</p>
     */
    ALLERGEN_VIA_COMPONENT(Kind.BLOCKING, "Nguyên liệu %s có chứa thành phần bạn đã khai dị ứng"),

    /**
     * Có nguyên liệu <em>chưa được rà soát</em> thành phần gây dị ứng (BR03, TC03).
     *
     * <p>Đây là thiếu dữ liệu, không phải kết luận món có hại — câu chữ phải nói đúng như vậy.</p>
     */
    UNSCREENED_INGREDIENT(Kind.BLOCKING, "Nguyên liệu %s chưa được rà soát thành phần gây dị ứng"),

    /** Nguyên liệu người dùng đã chọn tránh vì lý do không phải dị ứng. */
    HARD_EXCLUSION(Kind.BLOCKING, "Có %s — nguyên liệu bạn đã chọn tránh"),

    /** Không hợp chế độ ăn bắt buộc. Cùng hạng với dị ứng: không bao giờ nới ra để có kết quả. */
    DIET_PATTERN_CONFLICT(Kind.BLOCKING, "Không hợp chế độ ăn %s bạn đã chọn"),

    /**
     * Người dùng đã bấm "Không gợi ý món này nữa".
     *
     * <p>Món bị loại khỏi ứng viên chứ không phải bị trừ điểm P (QĐ-03).</p>
     */
    USER_BLOCKED_RECIPE(Kind.BLOCKING, "Bạn đã chọn không gợi ý món này"),

    /** Vượt giới hạn thời gian, <strong>chỉ khi</strong> người dùng bật giới hạn cứng (BR13). */
    OVER_TIME_LIMIT(Kind.BLOCKING, "Nấu lâu hơn %s phút bạn đặt làm giới hạn cứng"),

    /** Vượt ngân sách bữa, <strong>chỉ khi</strong> người dùng bật giới hạn cứng (BR13). */
    OVER_BUDGET(Kind.BLOCKING, "Vượt ngân sách bữa bạn đặt làm giới hạn cứng"),

    // --- Vì sao món được gợi ý ----------------------------------------------------------------

    /** Món người dùng đã tự đánh dấu yêu thích. Yêu thích nâng điểm, <em>không</em> bỏ qua bước lọc. */
    FAVORITE(Kind.HIGHLIGHT, "Món bạn đã đánh dấu yêu thích"),

    /** Hợp mô tả mùi và kết cấu người dùng tự khai ở bước 3 onboarding. */
    MATCHES_TOLERANCE(Kind.HIGHLIGHT, "Hợp khẩu vị bạn đã chọn"),

    QUICK_TO_COOK(Kind.HIGHLIGHT, "Khoảng %s phút nấu"),

    WITHIN_BUDGET(Kind.HIGHLIGHT, "Trong khoảng ngân sách bạn đặt"),

    /** Đa dạng: món chưa xuất hiện trong hai ngày gần đây. */
    NOT_RECENTLY_EATEN(Kind.HIGHLIGHT, "Chưa có trong thực đơn hai ngày gần đây"),

    /**
     * Khớp phần năng lượng <em>dự kiến</em> của bữa theo template.
     *
     * <p>Tỉ lệ bữa là giả định kỹ thuật để xếp hạng, không phải quy tắc y khoa.</p>
     */
    ENERGY_FIT(Kind.HIGHLIGHT, "Gần mức năng lượng dự kiến của bữa"),

    /**
     * Khớp phần vi chất tham chiếu của bữa.
     *
     * <p><strong>Không</strong> có nghĩa món hay ngày đã đủ chất — con số tham chiếu là mức cả
     * ngày của một nhóm tuổi, không phải mức phân bổ cho một người cụ thể.</p>
     */
    MICRONUTRIENT_FIT(Kind.HIGHLIGHT, "Khớp phần vi chất tham chiếu của bữa"),

    // --- Nói về cả lượt gợi ý -----------------------------------------------------------------

    /**
     * Không còn món nào qua được điều kiện bắt buộc.
     *
     * <p><strong>Không phải mã lỗi.</strong> Trả HTTP 200 kèm lý do và lời mời nới các ưu tiên
     * <em>mềm</em> (thời gian, ngân sách, món không thích). Không bao giờ nới dị ứng hay chế độ ăn
     * bắt buộc để có kết quả (bất biến 5).</p>
     */
    NO_MATCH(Kind.RESULT, "Chưa tìm được món hợp với điều kiện bắt buộc của bạn"),

    /** Đã chạm trần số món duyệt trong một lượt; còn món khác chưa được xét. */
    SEARCH_LIMIT(Kind.RESULT, "Đã đạt giới hạn số món duyệt trong một lượt"),

    /**
     * Không tiêu chí nào dùng được — danh sách chỉ là thứ tự ổn định của bộ món mẫu.
     *
     * <p>Phải nói ra, nếu không người dùng tưởng danh sách đã được cá nhân hóa.</p>
     */
    BASIC_SUGGESTION(Kind.RESULT, "Đây là gợi ý cơ bản, chưa xếp theo hồ sơ của bạn"),

    /**
     * Bộ lọc chế độ ăn đang <strong>chặt hơn mức cần</strong> vì danh mục chưa đủ chi tiết.
     *
     * <p>{@link FoodGroup#MEAT_FISH_EGG} gộp thịt, cá và trứng làm một, nên không tách được "ăn
     * chay có trứng sữa" khỏi "không ăn thịt đỏ". Bước lọc chọn hướng chặt — thà danh sách ngắn
     * hơn cần còn hơn dọn lên một món người ta không ăn — và nói ra bằng mã này thay vì im lặng.
     * Gỡ được khi danh mục có nhãn chế độ ăn riêng cho từng nguyên liệu.</p>
     */
    DIET_FILTER_COARSE(Kind.RESULT,
            "Danh mục chưa tách cá và trứng khỏi thịt, nên danh sách đang lọc chặt hơn mức cần"),

    /**
     * Hồ sơ còn ở trạng thái "chưa rõ dị ứng" (BR01).
     *
     * <p>Danh sách vẫn xem được, nhưng <strong>không được gắn nhãn</strong> "đã kiểm tra theo dị
     * ứng của bạn". Mã này là cách bước gợi ý nói lại điều đó cho giao diện.</p>
     */
    ALLERGY_STATUS_UNCONFIRMED(Kind.RESULT,
            "Bạn chưa xác nhận tình trạng dị ứng, danh sách này chưa lọc theo dị ứng");

    /** Nhóm của lý do — ngăn một mã chặn bị đem ra làm lời khen. */
    public enum Kind {
        /** Vì sao món bị loại. */
        BLOCKING,
        /** Vì sao món được gợi ý. Tối đa hai mã loại này hiển thị cho mỗi món. */
        HIGHLIGHT,
        /** Nói về cả lượt gợi ý, không gắn vào món nào. */
        RESULT
    }

    private final Kind kind;
    private final String template;

    RecommendationReason(Kind kind, String template) {
        this.kind = kind;
        this.template = template;
    }

    public Kind kind() {
        return kind;
    }

    /** Mẫu câu thô, còn chỗ trống. Nơi gọi dùng {@link #message(String)} chứ không ghép tay. */
    public String template() {
        return template;
    }

    /** Câu hoàn chỉnh cho lý do không có chỗ trống. */
    public String message() {
        return message(null);
    }

    /**
     * Câu hoàn chỉnh, điền {@code detail} vào chỗ trống của mẫu.
     *
     * <p>Mẫu không có chỗ trống thì {@code detail} bị bỏ qua — nhờ vậy nơi gọi không phải nhớ mã
     * nào cần tham số, và không bao giờ sinh ra câu kiểu "Món bạn đã đánh dấu yêu thích null".</p>
     */
    public String message(String detail) {
        if (!template.contains("%s")) {
            return template;
        }
        return template.formatted(detail == null ? "" : detail);
    }
}
