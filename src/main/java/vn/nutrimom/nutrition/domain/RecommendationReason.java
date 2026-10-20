package vn.nutrimom.nutrition.domain;

/**
 * Lý do có thể hiển thị cho một món, cho cả một lượt gợi ý, hoặc cho cả một kế hoạch một ngày.
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

    /**
     * Đã chạm giới hạn tìm kiếm của một lượt; còn phương án chưa được xét.
     *
     * <p>Hai nơi phát mã này chạm hai loại trần khác nhau — nhánh 4 chạm trần <em>số món duyệt</em>,
     * nhánh 5 chạm <em>hạn thời gian</em> của bộ sinh — nhưng điều phải nói với người dùng thì giống
     * hệt nhau, nên câu chữ cố ý không nhắc tới trần nào: hết lượt tìm
     * <strong>không có nghĩa là không tồn tại thực đơn phù hợp</strong> (TC19). Khẳng định "không có
     * món nào hợp với bạn" khi mới chỉ hết thời gian tìm là một lời nói dối rất dễ tin.</p>
     */
    SEARCH_LIMIT(Kind.RESULT,
            "Đã đạt giới hạn tìm kiếm của một lượt — còn phương án chưa được xét, "
                    + "không có nghĩa là không tồn tại thực đơn phù hợp"),

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
            "Bạn chưa xác nhận tình trạng dị ứng, danh sách này chưa lọc theo dị ứng"),

    // --- Nói về cả một kế hoạch một ngày (nhánh 5) --------------------------------------------
    //
    // Cùng nằm ở enum này chứ không tách thành một danh mục lý do riêng cho kế hoạch: mã lý do là
    // thứ đi ra tới giao diện, và hai bảng mã song song sẽ sớm có hai mã cùng nghĩa khác tên. Nhóm
    // Kind.RESULT vốn đã là "nói về cả một lượt", còn một lượt sinh thực đơn thì cũng là một lượt.

    /**
     * Một vai trò bắt buộc của bữa không còn ứng viên nào sau bước lọc (TC18).
     *
     * <p>{@code detail} phải nêu <strong>vai trò nào, bữa nào và bộ lọc nào</strong> gây giới hạn.
     * Một câu "không tìm được thực đơn" trống không thì người dùng không biết nên nới cái gì — mà
     * nới đúng chỗ chính là việc duy nhất họ có thể làm.</p>
     */
    ROLE_NO_CANDIDATE(Kind.RESULT, "Không còn món nào cho %s"),

    /**
     * Người dùng chỉ lập một phần các bữa trong ngày (TC23).
     *
     * <p>Kế hoạch <strong>không</strong> chuẩn hóa các bữa đã chọn lên thành toàn bộ năng lượng
     * ngày: mẹ bầu vẫn ăn những bữa ngoài kế hoạch, và coi hai bữa là cả ngày sẽ đẩy khẩu phần của
     * hai bữa đó lên quá mức.</p>
     */
    PARTIAL_DAY_PLAN(Kind.RESULT,
            "Đây là kế hoạch một phần ngày (%s) — các bữa ngoài kế hoạch không nằm trong tổng này"),

    /** Tổng ngày còn chất chưa đủ dữ liệu, nên chỉ gồm phần đã biết (BR08). */
    NUTRITION_DATA_INCOMPLETE(Kind.RESULT,
            "Một số chất chưa đủ dữ liệu, tổng của ngày chỉ gồm phần đã biết"),

    /** Chi phí ước tính của ngày vượt ngân sách <em>mềm</em> người dùng đặt. */
    DAY_OVER_BUDGET(Kind.RESULT,
            "Chi phí ước tính của ngày khoảng %s đồng, cao hơn ngân sách bạn đặt"),

    /**
     * Có bữa vượt thời gian mong muốn, tính trên <strong>toàn bữa</strong>.
     *
     * <p>MVP ước tính bảo thủ: thời gian một bữa là <em>tổng</em> thời gian các món, chưa giả định
     * các bước nấu chạy song song.</p>
     */
    MEAL_OVER_TIME(Kind.RESULT, "Có bữa nấu lâu hơn %s phút bạn muốn dành cho một bữa"),

    /**
     * Bật giới hạn ngân sách <em>cứng</em> nhưng danh mục còn món chưa quy đổi được giá.
     *
     * <p>Không coi dữ liệu thiếu là đã đạt giới hạn: những món đó không được đưa vào kế hoạch, và
     * người dùng được nói thẳng là có thể bổ sung giá hoặc tắt giới hạn — <strong>họ chọn</strong>,
     * hệ thống không tự nới.</p>
     */
    STRICT_BUDGET_WITHOUT_PRICE(Kind.RESULT,
            "Bạn đang bật giới hạn ngân sách cứng nhưng %s món chưa có giá quy đổi được, "
                    + "nên chúng không được đưa vào kế hoạch — hãy bổ sung giá hoặc tắt giới hạn"),

    /** Món đã khóa không đặt được vào cấu trúc nào của bữa (vai trò không thuộc template nào). */
    LOCKED_ITEM_UNPLACEABLE(Kind.RESULT, "Món bạn khóa ở %s không hợp với cấu trúc nào của bữa đó"),

    // --- Nói về cả một kế hoạch nhiều ngày (nhánh 9) ------------------------------------------

    /**
     * Giới hạn thực phẩm theo tuần đã được kiểm trên <strong>tổng của cả kế hoạch</strong>.
     *
     * <p>Phát ra cả khi còn thừa mức: con số "đã dùng / mức tuần" là thứ cho thấy điều kiện đã
     * chạy trên toàn kế hoạch chứ không riêng một ngày. Im lặng thì người dùng không phân biệt
     * được "trong mức" với "chưa ai kiểm" — và đây là một khuyến cáo an toàn, không phải một tuỳ
     * chọn.</p>
     */
    WEEKLY_FOOD_LIMIT_CHECKED(Kind.RESULT, "Đã kiểm giới hạn theo tuần cho cả kế hoạch: %s"),

    /**
     * Một nhóm đã dùng hết mức của tuần, nên phần còn lại của kế hoạch không nhận thêm nhóm đó.
     *
     * <p>Đây là <strong>điều kiện cứng</strong>, cùng hạng với dị ứng: không nới ra để kế hoạch
     * trông đa dạng hơn (bất biến 5).</p>
     */
    WEEKLY_FOOD_LIMIT_REACHED(Kind.RESULT,
            "Đã dùng hết mức theo tuần của %s, các bữa còn lại không nhận thêm nhóm này"),

    /**
     * Cùng một món xuất hiện trong <strong>hai ngày gần nhau</strong> của kế hoạch.
     *
     * <p>Không tính những mục người dùng chủ động lập lịch lặp — họ đã nói rõ là muốn ăn lại món
     * đó, và báo cho họ biết điều họ vừa yêu cầu là tiếng ồn. Món lặp vẫn hợp lệ: điểm đa dạng đã
     * trừ 40 điểm cho nó, và ghi chú này chỉ nói ra chỗ bộ sinh đã phải chấp nhận lặp vì danh mục
     * không còn lựa chọn nào khác.</p>
     */
    REPEATED_RECIPE_ACROSS_DAYS(Kind.RESULT,
            "Thực đơn lặp lại %s trong hai ngày gần nhau — danh mục chưa đủ món cho vai trò đó"),

    // --- Nói về danh sách mua sắm (nhánh 7) ---------------------------------------------------
    //
    // Vẫn ở enum này, cùng lý do đã viết cho nhóm kế hoạch: mã lý do là thứ đi ra tới giao diện,
    // và hai bảng mã song song sẽ sớm có hai mã cùng nghĩa khác tên. Tất cả đều Kind.RESULT —
    // chúng nói về một dòng hoặc cả một danh sách, không bao giờ là lời khen hay lời chặn một món.

    /**
     * Lượng cần đã tăng sau khi người dùng đổi món, trong khi dòng đã được đánh dấu đã mua (TC22).
     *
     * <p>Dòng <strong>giữ nguyên</strong> trạng thái đã mua: người ta đã đi chợ thật, và xóa dấu
     * đó đi để bắt mua lại từ đầu là nói sai về việc đã xảy ra. Thứ phải nói là phần còn thiếu.</p>
     */
    SHOPPING_NEED_MORE(Kind.RESULT, "Thực đơn đã đổi nên cần thêm %s so với lượng bạn đã có"),

    /**
     * Nguyên liệu <strong>chưa có giá</strong> quy đổi được, nên dòng này không nằm trong tổng.
     *
     * <p>Chưa có giá khác với rẻ. Dòng vẫn phải mua, chỉ là hệ thống không biết nó bao nhiêu tiền
     * — và cộng nó vào tổng như số 0 sẽ cho ra một tổng thấp hơn thực tế mà nhìn vẫn đầy đủ.</p>
     */
    SHOPPING_PRICE_UNAVAILABLE(Kind.RESULT, "Chưa có giá cho %s"),

    /** Tổng chi phí của danh sách chỉ gồm những dòng đã có giá quy đổi được. */
    SHOPPING_COST_INCOMPLETE(Kind.RESULT,
            "Tổng chi phí chưa đầy đủ: %s nguyên liệu chưa có giá quy đổi được, "
                    + "và đây là giá ước tính từ dữ liệu mẫu, không phải báo giá cửa hàng"),

    /**
     * Nhắc đọc nhãn thành phần ở <strong>mức nguyên liệu</strong>, cho gia vị và đồ đóng gói.
     *
     * <p>Bài học từ Mealime: lọc công thức theo dị ứng <em>không</em> thay thế việc đọc nhãn sản
     * phẩm. Một gói gia vị nêm sẵn hay một chai nước mắm có thể chứa thành phần mà danh mục chưa
     * rà hết, nên chỗ duy nhất kiểm được là cái nhãn trên tay người mua.</p>
     */
    SHOPPING_READ_PRODUCT_LABEL(Kind.RESULT,
            "Hãy đọc nhãn thành phần của %s khi mua — lọc công thức không thay cho việc đọc nhãn"),

    /** Dòng đã mua nhưng nguyên liệu không còn trong thực đơn sau khi người dùng đổi món. */
    SHOPPING_ITEM_NO_LONGER_NEEDED(Kind.RESULT,
            "Thực đơn đã đổi, %s không còn cần cho các món hiện tại"),

    /** Dòng tự nhập: nằm ngoài thực đơn, nên nằm ngoài mọi phép tính dinh dưỡng (bất biến 3 và 4). */
    SHOPPING_CUSTOM_LINE(Kind.RESULT,
            "Dòng bạn tự thêm — không nằm trong thực đơn và không tính vào dinh dưỡng"),

    // --- Nói về nhật ký một ngày (nhánh 8) ----------------------------------------------------
    //
    // Vẫn ở enum này, cùng lý do đã viết cho hai nhóm trên. Tất cả đều Kind.RESULT: chúng nói về một
    // dòng nhật ký hoặc về tổng của một ngày, không bao giờ là lời khen hay lời chặn một món.

    /**
     * Tổng "đã ghi" là tổng của <strong>những món đã ghi</strong>, không phải lượng ăn cả ngày.
     *
     * <p>Phát ra ở <strong>mọi</strong> lượt đọc nhật ký, kể cả khi đã ghi đủ ba bữa. Hệ thống chỉ
     * biết những gì người dùng bấm vào; mọi thứ khác họ ăn trong ngày nó không hề thấy. Gắn nhãn
     * "tổng lượng ăn cả ngày" lên con số này là một lời khẳng định mà không có dữ liệu nào đỡ, và
     * nó rất dễ tin vì con số trông đầy đủ.</p>
     */
    DIARY_LOGGED_TOTAL_ONLY(Kind.RESULT,
            "Tổng của các món bạn đã ghi — không phải tổng lượng ăn cả ngày"),

    /**
     * Kế hoạch của ngày còn bữa chưa được ghi.
     *
     * <p>Bữa chưa ghi <strong>không tự thành đã ăn</strong> (bất biến 3): nó vẫn nằm ở tổng dự kiến
     * và không góp một con số nào vào tổng đã ghi. Mã này chỉ nói ra khoảng cách đó, không mời ghi
     * hộ.</p>
     */
    DIARY_PLANNED_MEAL_NOT_LOGGED(Kind.RESULT,
            "Kế hoạch hôm nay còn %s bữa chưa được ghi — chúng không nằm trong tổng đã ghi"),

    /**
     * Có dòng là món <strong>ngoài bộ dữ liệu</strong>: chỉ có tên và lượng, không có số liệu.
     *
     * <p>Hệ thống không suy vi chất từ tên món, nên tổng của ngày thiếu đúng phần của những dòng
     * đó và mang {@link CoverageStatus#PARTIAL}. Nói ra chứ không lặng lẽ bỏ qua: người dùng cần
     * biết tổng đang thiếu cái gì.</p>
     */
    DIARY_OFF_CATALOG_ENTRY(Kind.RESULT,
            "%s món bạn ghi nằm ngoài bộ dữ liệu — hệ thống chỉ lưu tên và lượng, "
                    + "không suy ra số liệu dinh dưỡng từ tên món"),

    /**
     * Có dòng người dùng tự đánh dấu "nguyên liệu đã đổi đáng kể, đây chỉ là ước tính".
     *
     * <p>Số vẫn tính theo công thức gốc vì không có nguồn nào khác, nhưng coi nó là chính xác cho
     * một món đã đổi nguyên liệu là một lời khẳng định không có cơ sở.</p>
     */
    DIARY_ESTIMATE_INCOMPLETE(Kind.RESULT,
            "%s món bạn đánh dấu là ước tính chưa đầy đủ vì nguyên liệu đã đổi so với công thức"),

    /**
     * Phần còn lại của ngày là <strong>ước tính để gợi ý</strong>, không phải lượng cần bù.
     *
     * <p>Đi kèm mỗi lần khối "phần còn lại" có số. Khoảng cách tới mức tham chiếu không phải một
     * đơn thuốc: nó là mức cả ngày của một nhóm tuổi, và ép bù trọn khoảng cách đó bằng một món là
     * đúng thứ mục 5 của nhánh cấm.</p>
     */
    DIARY_REMAINING_ESTIMATE(Kind.RESULT,
            "Phần còn lại tính từ những món bạn đã ghi — đây là ước tính để gợi ý thêm bữa phụ, "
                    + "không phải lượng cần bù trong hôm nay"),

    /**
     * Nhật ký hôm nay quá thiếu dữ liệu để nói về khẩu phần cả ngày.
     *
     * <p>Lúc này khu vực gợi ý phải quay về <strong>gợi ý đa dạng thông thường</strong> và tuyệt
     * đối không khẳng định đã đánh giá khẩu phần cả ngày.</p>
     */
    DIARY_TOO_SPARSE(Kind.RESULT,
            "Nhật ký hôm nay chưa đủ dữ liệu để nói về khẩu phần cả ngày — "
                    + "gợi ý bên dưới là gợi ý đa dạng thông thường"),

    // --- Nói về một kế hoạch sau khi hồ sơ đổi (nhánh 10) -------------------------------------

    /**
     * Hồ sơ đã đổi và kế hoạch còn mục <strong>chưa ăn</strong> không hợp điều kiện bắt buộc mới.
     *
     * <p>Phát ra ở mức kế hoạch, đi kèm {@code lifecycle_state = NEEDS_UPDATE} và cờ xung đột ở
     * từng mục. Câu chữ nói <em>việc cần làm</em> chứ không phán xét: hệ thống không tự đổi món và
     * không tự mở khóa mục người dùng đã giữ (BR10) — nó chỉ chỉ đúng chỗ cần đổi.</p>
     *
     * <p>{@code detail} là số mục cần đổi. Cố ý không liệt kê tên món ở đây: danh sách đó nằm ngay
     * trên từng mục, và lặp lại nó ở đầu trang chỉ làm một kế hoạch bảy ngày thành một đoạn văn.</p>
     */
    PLAN_NEEDS_UPDATE_AFTER_PROFILE_CHANGE(Kind.RESULT,
            "Hồ sơ của bạn đã đổi: %s món trong thực đơn không còn hợp điều kiện bắt buộc, "
                    + "hãy đổi những món được đánh dấu trước khi dùng tiếp"),

    /**
     * Lượt tạo lại đã <strong>giữ nguyên</strong> một bữa vì nó đã được ghi vào nhật ký (BR11).
     *
     * <p>Phải nói ra: người dùng bấm "Tạo lại" và mong cả ngày đổi, nên một bữa không đổi mà không
     * ai giải thích trông như một lỗi. Lý do thì ngược lại — bữa đó đã ăn rồi, và lập lại một việc
     * đã xảy ra là thứ duy nhất ở đây thực sự sai (bất biến 3).</p>
     */
    PLAN_KEPT_EATEN_MEAL(Kind.RESULT,
            "Giữ nguyên %s vì bữa này đã được ghi vào nhật ký — tạo lại không sửa bữa đã ăn");

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
