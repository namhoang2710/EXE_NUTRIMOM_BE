-- Thực đơn một ngày: cấu trúc bữa, món đã chọn, và bản xem trước có thời hạn
-- (docs/nutrition/branch-05-plan-generation.md).
--
-- Số V60 lấy từ chính database dùng chung lúc bắt đầu nhánh (SELECT MAX(version::numeric) FROM
-- app.flyway_schema_history trả 59) — xem docs/nutrition/_CONTRACT.md mục 2. Kiểm lại con số này
-- ngay trước khi merge: người khác có thể đã chiếm số trong lúc nhánh này đang làm.
--
-- Bốn bảng, ba tầng:
--   meal_plans         — một kế hoạch ĐÃ LƯU của một người
--   meal_slots         — vị trí bữa trong ngày (QĐ-02: có cột sequence cho bữa phụ)
--   meal_plan_items    — món đã chọn cho một bữa, kèm SNAPSHOT để seed không viết lại lịch sử
--   meal_plan_previews — bản xem trước CHƯA LƯU, sống 30 phút (QĐ-01)

-- ---------------------------------------------------------------------------------------------
-- Kế hoạch đã lưu
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.meal_plans (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,

    -- Nhánh này chỉ lập MỘT ngày nên end_date = start_date. Hai cột vẫn tách ra vì nhánh 9 lập ba
    -- và bảy ngày trên đúng bảng này, và đổi hình dạng một bảng đã có dữ liệu đắt hơn nhiều so với
    -- việc giữ sẵn một cột đang bằng nhau.
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,

    -- KẾT QUẢ SINH, không phải trạng thái lưu. READY_WITH_NOTES vẫn là một kế hoạch dùng được, chỉ
    -- còn điểm cần xem (chi phí, thời gian, dữ liệu thiếu).
    generation_status VARCHAR(20) NOT NULL,
    -- TRẠNG THÁI LƯU, hoàn toàn khác generation_status. Nhánh 10 chuyển sang NEEDS_UPDATE khi hồ sơ
    -- đổi. Gộp hai nhóm này làm một cột sẽ mất khả năng nói "kế hoạch vẫn tốt nhưng hồ sơ đã đổi" —
    -- đúng tình huống nhánh 10 phải xử lý.
    lifecycle_state VARCHAR(20) NOT NULL,

    -- Tăng sau MỖI thao tác sửa kế hoạch (nhánh 6). Optimistic lock: hai thiết bị cùng sửa thì bên
    -- cầm số cũ nhận VERSION_CONFLICT thay vì ghi đè im lặng.
    plan_version INT NOT NULL DEFAULT 1,
    -- Phiên bản hồ sơ và tập quy tắc đã sinh ra kế hoạch này. Nhánh 10 so hai số này với hiện tại
    -- để biết kế hoạch còn hợp lệ hay phải mời người dùng tạo lại.
    profile_version INT NOT NULL,
    rules_version VARCHAR(64) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_meal_plans PRIMARY KEY (id),
    CONSTRAINT fk_meal_plans_user FOREIGN KEY (user_id) REFERENCES app.users(id),
    CONSTRAINT ck_meal_plans_generation_status CHECK (
        generation_status IN ('READY', 'READY_WITH_NOTES')),
    CONSTRAINT ck_meal_plans_lifecycle_state CHECK (
        lifecycle_state IN ('SAVED', 'NEEDS_UPDATE')),
    CONSTRAINT ck_meal_plans_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_meal_plans_plan_version CHECK (plan_version >= 1),
    CONSTRAINT ck_meal_plans_profile_version CHECK (profile_version >= 0)
);

-- Bảng này KHÔNG nhận NO_MATCH, nên ck_meal_plans_generation_status chỉ liệt kê hai giá trị:
-- "không tìm được thực đơn" là kết quả nghiệp vụ trả HTTP 200 kèm lý do, không phải thứ để lưu
-- thành kế hoạch của người dùng. Khoá ngay ở database để một lỗi lập trình ở lớp trên không tạo ra
-- những kế hoạch rỗng mà giao diện vẫn mở ra được.

-- ---------------------------------------------------------------------------------------------
-- Vị trí bữa trong ngày (QĐ-02)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.meal_slots (
    id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,
    local_date DATE NOT NULL,
    meal_type VARCHAR(20) NOT NULL,

    -- QĐ-02. Đặc tả mục 13 nói "bữa phụ dùng sequence" nhưng danh sách trường không có cột này.
    -- Thiếu nó thì ba bữa phụ trong một ngày đụng nhau ở unique (plan, ngày, loại bữa) và người
    -- dùng chỉ ăn vặt được một lần.
    sequence SMALLINT NOT NULL DEFAULT 1,

    -- Cấu trúc đã chọn cho bữa, dạng '<loại bữa>:<chế độ>' vd 'LUNCH:ROLE_SET'. Ghi lại CHẾ ĐỘ chứ
    -- không chỉ danh sách món vì BR06 cấm trộn: một bữa là một món hoàn chỉnh HOẶC một tập vai trò,
    -- và nhánh 6 phải biết bữa đang theo cấu trúc nào mới đổi món đúng vai trò được.
    template_code VARCHAR(40) NOT NULL,

    CONSTRAINT pk_meal_slots PRIMARY KEY (id),
    CONSTRAINT fk_meal_slots_plan FOREIGN KEY (plan_id)
        REFERENCES app.meal_plans(id) ON DELETE CASCADE,
    -- Bộ BỐN, không phải bộ ba: thêm sequence vào khoá duy nhất là toàn bộ điểm của QĐ-02.
    CONSTRAINT uq_meal_slots_position UNIQUE (plan_id, local_date, meal_type, sequence),
    CONSTRAINT ck_meal_slots_meal_type CHECK (
        meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK')),
    CONSTRAINT ck_meal_slots_sequence CHECK (sequence BETWEEN 1 AND 3),
    -- Bữa chính luôn sequence = 1. Slot thứ hai cùng loại là lỗi dữ liệu chứ không phải bữa phụ thứ
    -- hai: người ta không ăn hai bữa trưa, và cho phép nó sẽ làm tỉ lệ năng lượng 30% bị nhân đôi.
    CONSTRAINT ck_meal_slots_single_main_meal CHECK (
        meal_type = 'SNACK' OR sequence = 1)
);

-- ---------------------------------------------------------------------------------------------
-- Món đã chọn
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.meal_plan_items (
    id VARCHAR(36) NOT NULL,
    slot_id VARCHAR(36) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    -- Phiên bản công thức LÚC chọn. Cùng với nutrient_snapshot bên dưới, đây là thứ giữ cho con số
    -- đã trình bày với người dùng không đổi khi seed sửa công thức về sau (mục versioning).
    recipe_version INT NOT NULL,

    servings NUMERIC(6, 2) NOT NULL,
    -- Nhánh 6 khoá món để tạo lại mà giữ nguyên vị trí này. Ở nhánh 5 cờ này đến từ locked_items
    -- của yêu cầu — và món khoá VẪN phải qua bước lọc (bất biến 2).
    locked BOOLEAN NOT NULL DEFAULT FALSE,

    -- Ảnh chụp CÔNG THỨC (RecipeSnapshot của nhánh 2): nguyên liệu, lượng gram, bảng dinh dưỡng của
    -- từng nguyên liệu tại thời điểm chọn.
    --
    -- Cố ý lưu snapshot ĐẦU VÀO chứ không lưu con số tổng: tổng được dựng lại bằng chính
    -- NutritionCalculator của nhánh 2 (bất biến 4 — một bộ tính duy nhất), nên tổng API trả về
    -- không bao giờ lệch với tổng tính từ snapshot các mục. Lưu sẵn tổng thì có hai nguồn sự thật,
    -- và lúc chúng lệch nhau không ai biết bên nào đúng.
    nutrient_snapshot JSONB NOT NULL,
    -- Chi phí ước tính của mục này. NULL = CHƯA QUY ĐỔI ĐƯỢC GIÁ, không phải miễn phí: một món chưa
    -- ai nhập giá không phải là một món rẻ (xem RecipeCostEstimator).
    cost_snapshot NUMERIC(12, 2) NULL,

    CONSTRAINT pk_meal_plan_items PRIMARY KEY (id),
    CONSTRAINT fk_meal_plan_items_slot FOREIGN KEY (slot_id)
        REFERENCES app.meal_slots(id) ON DELETE CASCADE,
    CONSTRAINT fk_meal_plan_items_recipe FOREIGN KEY (recipe_id) REFERENCES app.recipes(id),
    -- Số phần phải > 0. Mọi phép tính dinh dưỡng đều nhân với số này, và 0 phần là một mục không
    -- tồn tại chứ không phải một mục rỗng (TC24).
    CONSTRAINT ck_meal_plan_items_servings CHECK (servings > 0),
    CONSTRAINT ck_meal_plan_items_cost CHECK (cost_snapshot IS NULL OR cost_snapshot >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Bản xem trước (QĐ-01)
-- ---------------------------------------------------------------------------------------------

-- Bảng chứ không phải cache: sống qua restart, mở DB ra xem được lúc demo, không thêm hạ tầng —
-- repo hiện chỉ có InMemoryRateLimiterStore, không có Redis.
CREATE TABLE app.meal_plan_previews (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,

    -- Idempotency key THẬT (_CONTRACT.md mục 6), nằm trong body chứ không phải header X-Request-Id.
    -- Unique cùng user_id chính là cách xử lý "mạng chậm nên bấm hai lần": lần thứ hai trả lại đúng
    -- bản ghi cũ, không sinh bản thứ hai và không chạy lại cả lượt tìm kiếm.
    request_id VARCHAR(64) NOT NULL,

    profile_version INT NOT NULL,
    rules_version VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,

    -- Nguyên văn body xem trước. Lưu cả bản NO_MATCH: lần gửi lại cùng request_id phải nhận lại
    -- đúng câu trả lời cũ kèm lý do, chứ không phải một lượt tìm mới có thể cho kết quả khác.
    payload JSONB NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- TTL 30 phút. Dọn bằng xóa lười lúc đọc; thêm job định kỳ về sau không phải đổi schema.
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Kế hoạch đã lưu từ chính bản xem trước này; NULL khi người dùng chưa lưu.
    --
    -- Không có trong danh sách cột của tài liệu nhánh; thêm vào vì nó trả lời đúng một yêu cầu khác
    -- của chính tài liệu đó: "nút tạo chặn nhấn trùng, lỗi mạng cho thử lại". Thiếu cột này thì hai
    -- lần POST /meal-plans cùng một preview_id tạo ra hai kế hoạch giống hệt nhau và người dùng
    -- không có cách nào biết cái nào là thật.
    saved_plan_id VARCHAR(36) NULL,

    CONSTRAINT pk_meal_plan_previews PRIMARY KEY (id),
    CONSTRAINT fk_meal_plan_previews_user FOREIGN KEY (user_id) REFERENCES app.users(id),
    CONSTRAINT fk_meal_plan_previews_plan FOREIGN KEY (saved_plan_id)
        REFERENCES app.meal_plans(id),
    CONSTRAINT uq_meal_plan_previews_request UNIQUE (user_id, request_id),
    CONSTRAINT ck_meal_plan_previews_status CHECK (
        status IN ('READY', 'READY_WITH_NOTES', 'NO_MATCH')),
    CONSTRAINT ck_meal_plan_previews_expiry CHECK (expires_at > created_at),
    -- Bản NO_MATCH không có gì để lưu, nên nó không bao giờ trỏ tới một kế hoạch.
    CONSTRAINT ck_meal_plan_previews_no_match_unsaved CHECK (
        status <> 'NO_MATCH' OR saved_plan_id IS NULL)
);

-- ---------------------------------------------------------------------------------------------
-- Index
-- ---------------------------------------------------------------------------------------------

-- "Thực đơn của tôi quanh ngày này" — lối vào duy nhất của màn hình kế hoạch.
CREATE INDEX ix_meal_plans_user_dates ON app.meal_plans(user_id, start_date DESC);

-- Nạp cả ngày: slot theo kế hoạch, rồi món theo slot.
CREATE INDEX ix_meal_slots_plan_date ON app.meal_slots(plan_id, local_date);
CREATE INDEX ix_meal_plan_items_slot ON app.meal_plan_items(slot_id);

-- Dọn bản xem trước hết hạn.
CREATE INDEX ix_meal_plan_previews_expires ON app.meal_plan_previews(expires_at);
