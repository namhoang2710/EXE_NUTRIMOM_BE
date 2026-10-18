-- Nhật ký đã ăn và phản hồi sau bữa (docs/nutrition/branch-08-diary.md).
--
-- Số V64 lấy theo _CONTRACT.md mục 2: SELECT MAX(version::numeric) FROM app.flyway_schema_history
-- trả 62 lúc bắt đầu nhánh, còn repo đã có V63 (danh sách mua sắm, nhánh 7) chưa chạy lên database
-- dùng chung. Số kế tiếp phải trống ở CẢ HAI phía, nên là 64 chứ không phải 63. Kiểm lại con số này
-- ngay trước khi merge: người khác có thể đã chiếm số trong lúc nhánh này đang làm.
--
-- Hai bảng, hai vai trò tách bạch:
--   diary_entries  — món THỰC TẾ ĐÃ ĂN, do người dùng xác nhận từng lần (BR16)
--   meal_feedbacks — cảm nhận sau bữa, có loại hết hạn (QĐ-03)

-- ---------------------------------------------------------------------------------------------
-- Nhật ký
-- ---------------------------------------------------------------------------------------------
--
-- BẤT BIẾN 3 — hai tập dữ liệu độc lập. Bảng này KHÔNG phải một cái nhìn khác của
-- app.meal_plan_items: mục kế hoạch là khẩu phần DỰ KIẾN, dòng ở đây là khẩu phần ĐÃ XÁC NHẬN, và
-- không có đường nào trong lược đồ biến cái thứ nhất thành cái thứ hai. Cụ thể:
--
--   * không có cột nào bắt một dòng nhật ký phải thuộc về một kế hoạch;
--   * source_plan_item_id là NGUỒN TÙY CHỌN và ON DELETE SET NULL — xóa kế hoạch KHÔNG xóa nhật ký,
--     vì bữa đã ăn là một sự việc đã xảy ra, không phải một hệ quả của bản kế hoạch;
--   * số phần ở đây là số người dùng xác nhận, nên nó được phép khác số phần của mục kế hoạch
--     (TC15: ăn nửa phần thì nhật ký tính một nửa, kế hoạch vẫn một phần).

CREATE TABLE app.diary_entries (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,

    -- Ngày theo lịch của người dùng, do client gửi lên. Cố ý KHÔNG suy từ created_at: một bữa tối
    -- ghi lúc 0 giờ 15 vẫn thuộc về ngày hôm trước, và máy chủ không biết người dùng đang ở múi nào.
    local_date DATE NOT NULL,
    meal_type VARCHAR(20) NOT NULL,

    -- NULL = MÓN NGOÀI BỘ DỮ LIỆU. Người dùng ăn thứ không có trong danh mục; hệ thống ghi lại tên
    -- và lượng họ gõ, và DỪNG Ở ĐÓ. Không có recipe_snapshot nghĩa là dòng này không đóng góp con
    -- số nào vào tổng — đúng điều nhánh yêu cầu: không tự sinh vi chất từ tên món.
    recipe_id VARCHAR(64) NULL,
    -- Phiên bản công thức tại thời điểm ghi. Đi cùng snapshot chứ không thay nó: snapshot giữ số
    -- liệu, con số này giữ chỗ để đối chiếu khi đi tìm lỗi.
    recipe_version INT NULL,

    -- Ảnh chụp NutritionSnapshots.RecipeSnapshot lúc ghi: tên, nguyên liệu, lượng gram, bảng dinh
    -- dưỡng. Tổng được dựng lại từ đây bằng chính NutritionCalculator mỗi lần đọc, nên chỉ có một
    -- bộ tính (bất biến 4) và seed sửa công thức về sau không viết lại bữa đã ăn (BR17, TC21).
    recipe_snapshot JSONB NULL,

    -- Số phần THỰC TẾ đã ăn, do người dùng xác nhận. Mặc định trên giao diện lấy từ kế hoạch, nhưng
    -- giá trị vào được tới đây chỉ sau một thao tác xác nhận (BR16) — không có đường nào để hệ
    -- thống tự điền.
    actual_servings NUMERIC(6, 2) NULL,

    -- Món ngoài bộ dữ liệu: tên món người dùng gõ, và lượng cũng bằng chữ ("một bát", "nửa hộp").
    -- Lượng để chữ chứ không ép thành số phần: quy "một bát" ra 1,0 phần của một công thức nào đó
    -- là bịa ra đúng cái con số mà nhánh này cấm bịa.
    custom_text VARCHAR(255) NULL,
    amount_text VARCHAR(64) NULL,

    -- Người dùng tự đánh dấu "nguyên liệu đã đổi đáng kể, đây chỉ là ước tính". Số liệu vẫn tính
    -- theo công thức gốc vì không có nguồn nào khác, nhưng tổng của ngày mang PARTIAL và giao diện
    -- phải nói ra — coi số của công thức gốc là chính xác cho một món đã đổi nguyên liệu là một
    -- lời khẳng định không có cơ sở.
    estimate_incomplete BOOLEAN NOT NULL DEFAULT FALSE,

    -- Mục kế hoạch đã gợi ra lần ghi này. TÙY CHỌN: nhật ký ghi được món ngoài thực đơn, và một
    -- dòng không có nguồn vẫn là một dòng đầy đủ.
    source_plan_item_id VARCHAR(36) NULL,

    -- Idempotency key (_CONTRACT.md mục 6), trong body chứ không phải header X-Request-Id. Unique
    -- cùng user_id: bấm "Đã ăn" hai lần vì mạng chậm trả lại đúng bản ghi cũ chứ không ghi hai bữa
    -- (TC16) — và ghi trùng ở đây không chỉ là một dòng thừa, nó làm tổng đã ăn của ngày gấp đôi.
    request_id VARCHAR(64) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_diary_entries PRIMARY KEY (id),
    CONSTRAINT fk_diary_entries_user FOREIGN KEY (user_id) REFERENCES app.users(id),
    CONSTRAINT fk_diary_entries_recipe FOREIGN KEY (recipe_id) REFERENCES app.recipes(id),
    -- SET NULL, KHÔNG phải CASCADE: xóa kế hoạch là bỏ phần dự kiến, không phải phủ nhận bữa đã ăn.
    CONSTRAINT fk_diary_entries_plan_item FOREIGN KEY (source_plan_item_id)
        REFERENCES app.meal_plan_items(id) ON DELETE SET NULL,
    CONSTRAINT uq_diary_entries_request UNIQUE (user_id, request_id),
    CONSTRAINT ck_diary_entries_meal_type CHECK (
        meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK')),
    CONSTRAINT ck_diary_entries_servings CHECK (
        actual_servings IS NULL OR actual_servings > 0),
    -- Hai dạng dòng, không có dạng thứ ba. Một dòng vừa trỏ tới công thức vừa mang tên tự do sẽ mập
    -- mờ đúng ở chỗ nguy hiểm nhất: nó có số liệu dinh dưỡng hay không. Ràng buộc này là chỗ database
    -- chặn một lỗi ở lớp trên gán số của một công thức cho một món người dùng chỉ gõ tên.
    CONSTRAINT ck_diary_entries_shape CHECK (
        (recipe_id IS NOT NULL AND recipe_version IS NOT NULL AND recipe_snapshot IS NOT NULL
            AND actual_servings IS NOT NULL AND custom_text IS NULL AND amount_text IS NULL)
        OR (recipe_id IS NULL AND recipe_version IS NULL AND recipe_snapshot IS NULL
            AND actual_servings IS NULL AND custom_text IS NOT NULL))
);

-- "Nhật ký ngày hôm nay của tôi" — lối vào duy nhất của màn hình nhật ký, và cũng là đường kiểm tra
-- chủ sở hữu. Đúng hai cột mà nhánh yêu cầu đánh index.
CREATE INDEX ix_diary_entries_user_date ON app.diary_entries(user_id, local_date);

-- ---------------------------------------------------------------------------------------------
-- Phản hồi sau bữa
-- ---------------------------------------------------------------------------------------------
--
-- QĐ-03 tách dứt khoát hai bảng sở thích:
--
--   app.user_recipe_preferences (V58)  lựa chọn CHỦ ĐỘNG, không có hạn: FAVORITE | BLOCKED
--   app.meal_feedbacks (bảng này)      cảm nhận SAU BỮA, có loại hết hạn: TASTY | DISLIKE | HARD_NOW
--
-- Chỉ cái tạm thời mới hết hạn, nên chỉ HARD_NOW ("khó ăn lúc này" — một trạng thái của thai kỳ)
-- mang expires_at. Và không dòng nào ở bảng này được biến thành dị ứng: dị ứng sống ở hồ sơ, do
-- người dùng khai, và là điều kiện an toàn không bao giờ suy ra từ một lần bấm "không thích".
--
-- Bảng ghi theo kiểu NHẬT KÝ: mỗi lần bấm là một dòng mới, không UPDATE dòng cũ. Người ta đổi ý
-- theo thời gian, và đè lên dòng cũ sẽ xóa mất chuỗi đổi ý đó. Lúc chấm điểm chỉ lấy MỘT phản hồi
-- gần nhất còn hiệu lực (QĐ-03), nên lịch sử dài không làm sai kết quả.

CREATE TABLE app.meal_feedbacks (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,

    -- TASTY (+10) · DISLIKE (−25) · HARD_NOW (−20). Mức cộng trừ nằm ở enum MealFeedbackSignal, KHÔNG
    -- chép vào đây: hai bảng số song song sẽ lệch nhau ngay lần chỉnh đầu tiên.
    type VARCHAR(20) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- NULL = không hết hạn. Xem ràng buộc bên dưới: NULL với TASTY/DISLIKE là bắt buộc chứ không
    -- phải tùy chọn, vì "không thích" là một ý kiến, không phải một cơn nghén.
    expires_at TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT pk_meal_feedbacks PRIMARY KEY (id),
    CONSTRAINT fk_meal_feedbacks_user FOREIGN KEY (user_id) REFERENCES app.users(id),
    CONSTRAINT fk_meal_feedbacks_recipe FOREIGN KEY (recipe_id) REFERENCES app.recipes(id),
    CONSTRAINT ck_meal_feedbacks_type CHECK (type IN ('TASTY', 'DISLIKE', 'HARD_NOW')),
    -- Hạn gắn chặt với loại, ép ở mức database: HARD_NOW phải có hạn, hai loại kia phải không có.
    -- Một dòng DISLIKE mang expires_at sẽ lặng lẽ tự biến mất sau vài ngày và không ai hiểu vì sao
    -- món đó quay lại trong gợi ý.
    CONSTRAINT ck_meal_feedbacks_expiry CHECK (
        (type = 'HARD_NOW' AND expires_at IS NOT NULL)
        OR (type <> 'HARD_NOW' AND expires_at IS NULL)),
    CONSTRAINT ck_meal_feedbacks_expiry_order CHECK (
        expires_at IS NULL OR expires_at > created_at)
);

-- "Phản hồi gần nhất của tôi cho món này" — đúng câu mà bước chấm điểm P hỏi mỗi lượt gợi ý.
CREATE INDEX ix_meal_feedbacks_user_recipe
    ON app.meal_feedbacks(user_id, recipe_id, created_at DESC);
