-- Hồ sơ ăn uống của mẹ bầu (docs/nutrition/branch-03-profile.md).
--
-- Số V58 lấy từ chính database dùng chung lúc bắt đầu nhánh (`SELECT MAX(version::numeric) FROM
-- app.flyway_schema_history` trả 57), KHÔNG lấy theo số file cao nhất trong repo — xem
-- docs/nutrition/_CONTRACT.md mục 2. Kiểm lại con số này ngay trước khi merge.
--
-- Khác hẳn nhóm bảng V57: đây là DỮ LIỆU NGƯỜI DÙNG. Khoá của hồ sơ dùng VARCHAR(36) UUID như
-- phần còn lại của hệ thống, còn mọi mã trỏ sang danh mục seed (allergen_id, ingredient_id,
-- recipe_id) giữ VARCHAR(64) đúng kiểu khoá bên đó.
--
-- profile_id CHÍNH LÀ app.pregnancies.id. Nhánh này không tạo lại hồ sơ thai kỳ (đã có từ V4);
-- mốc thai kỳ đọc thẳng từ bảng đó, ở đây chỉ gắn thêm phần ăn uống vào cùng một hồ sơ.

-- ---------------------------------------------------------------------------------------------
-- Hồ sơ ăn uống
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.eating_preferences (
    profile_id VARCHAR(36) NOT NULL,

    -- BA trạng thái, không phải boolean (BR01). UNKNOWN là mặc định của hồ sơ mới: hệ thống KHÔNG
    -- được tự kết luận người dùng không có dị ứng, nên "chưa khai" (UNKNOWN) và "đã khai là không
    -- có" (KNOWN_NONE) phải phân biệt được. Hồ sơ UNKNOWN vẫn xem được nội dung và công thức,
    -- nhưng danh sách gợi ý không được gắn nhãn "đã kiểm tra theo dị ứng của bạn".
    allergy_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',

    -- Bước 3 trở đi đều BỎ QUA ĐƯỢC, nên mọi cột dưới đây cho phép NULL. NULL ở đây nghĩa là
    -- "chưa khai", không phải "không giới hạn" — nơi dùng phải tự quyết định mặc định của mình
    -- thay vì đọc 0 ra khỏi cột trống (bất biến 1 của _CONTRACT.md).
    diet_pattern VARCHAR(30) NULL,
    daily_budget_vnd BIGINT NULL,
    max_minutes_per_meal INT NULL,
    -- Số bữa phụ mặc định khi lập kế hoạch (QĐ-02). Loại bữa chính nằm ở bảng con
    -- eating_preference_meal_types; cả hai chỉ là GIÁ TRỊ GỢI SẴN, lúc tạo kế hoạch vẫn chỉnh được.
    snack_count SMALLINT NULL,

    -- energy_inputs của đặc tả, trải phẳng thành cột thay vì một khối JSON: EnergyEstimator ở
    -- nhánh 2 báo thiếu theo TỪNG TÊN TRƯỜNG ("height_cm", "activity_category"), nên từng trường
    -- phải là một cột có ràng buộc riêng để giao diện chỉ đúng ô cần sửa.
    age_years INT NULL,
    height_cm NUMERIC(5, 2) NULL,
    pre_pregnancy_weight_kg NUMERIC(6, 2) NULL,
    current_weight_kg NUMERIC(6, 2) NULL,
    activity_category VARCHAR(20) NULL,

    -- Tăng đúng MỘT mỗi lần lưu có thay đổi ít nhất một trường ảnh hưởng gợi ý (gồm cả danh sách
    -- ở các bảng con và app.user_recipe_preferences). Kế hoạch lưu kèm số này để nhánh 10 biết
    -- kế hoạch cũ còn hợp lệ hay không.
    --
    -- Cố ý KHÔNG đặt cột này lên app.pregnancies: bảng đó thuộc module thai kỳ, đã có cột `version`
    -- làm optimistic lock, và thêm một cột version thứ hai mang nghĩa khác vào đó thì hai module
    -- phải cùng giữ một bất biến mà không bên nào thấy hết. Hồ sơ ăn uống tự giữ số của mình.
    profile_version INT NOT NULL DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_eating_preferences PRIMARY KEY (profile_id),
    CONSTRAINT fk_eating_preferences_profile FOREIGN KEY (profile_id)
        REFERENCES app.pregnancies(id),
    CONSTRAINT ck_eating_preferences_allergy_status CHECK (
        allergy_status IN ('KNOWN_NONE', 'KNOWN_LIST', 'UNKNOWN')),
    CONSTRAINT ck_eating_preferences_diet_pattern CHECK (
        diet_pattern IS NULL OR diet_pattern IN (
            'OMNIVORE', 'PESCATARIAN', 'LACTO_OVO_VEGETARIAN', 'VEGAN')),
    CONSTRAINT ck_eating_preferences_activity CHECK (
        activity_category IS NULL OR activity_category IN (
            'INACTIVE', 'LOW_ACTIVE', 'ACTIVE', 'VERY_ACTIVE')),
    CONSTRAINT ck_eating_preferences_budget CHECK (
        daily_budget_vnd IS NULL OR daily_budget_vnd > 0),
    CONSTRAINT ck_eating_preferences_minutes CHECK (
        max_minutes_per_meal IS NULL OR (max_minutes_per_meal >= 5 AND max_minutes_per_meal <= 600)),
    CONSTRAINT ck_eating_preferences_snack_count CHECK (
        snack_count IS NULL OR (snack_count >= 1 AND snack_count <= 3)),
    CONSTRAINT ck_eating_preferences_age CHECK (
        age_years IS NULL OR (age_years >= 10 AND age_years <= 70)),
    CONSTRAINT ck_eating_preferences_height CHECK (height_cm IS NULL OR height_cm > 0),
    CONSTRAINT ck_eating_preferences_pre_weight CHECK (
        pre_pregnancy_weight_kg IS NULL OR pre_pregnancy_weight_kg > 0),
    CONSTRAINT ck_eating_preferences_weight CHECK (
        current_weight_kg IS NULL OR current_weight_kg > 0),
    CONSTRAINT ck_eating_preferences_profile_version CHECK (profile_version >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Dị ứng người dùng khai
-- ---------------------------------------------------------------------------------------------

-- Thành phần gây dị ứng người dùng chọn từ app.allergens.
--
-- Chỉ có nghĩa khi allergy_status = KNOWN_LIST. KNOWN_NONE và UNKNOWN luôn đi với bảng này rỗng,
-- nhưng hai trạng thái đó KHÁC NHAU, nên không thể suy trạng thái từ việc bảng rỗng — phải đọc
-- cột allergy_status.
CREATE TABLE app.user_allergens (
    profile_id VARCHAR(36) NOT NULL,
    allergen_id VARCHAR(64) NOT NULL,
    -- "Unique hai mã": chọn một thành phần hai lần vẫn chỉ là một dòng.
    CONSTRAINT pk_user_allergens PRIMARY KEY (profile_id, allergen_id),
    CONSTRAINT fk_user_allergens_profile FOREIGN KEY (profile_id)
        REFERENCES app.eating_preferences(profile_id),
    CONSTRAINT fk_user_allergens_allergen FOREIGN KEY (allergen_id)
        REFERENCES app.allergens(id)
);

-- ---------------------------------------------------------------------------------------------
-- Ba danh sách của bước 2 và bước 3
-- ---------------------------------------------------------------------------------------------

-- Nguyên liệu phải tránh vì lý do KHÔNG PHẢI dị ứng (kiêng theo chỉ định, theo tín ngưỡng).
--
-- Tách khỏi disliked_ingredients bên dưới vì hai danh sách có số phận khác nhau khi hết món:
-- đây là điều kiện CỨNG, không bao giờ được nới lỏng để có kết quả (bất biến 5); còn "không
-- thích" là ưu tiên mềm, được phép mời người dùng nới ra.
CREATE TABLE app.eating_preference_hard_exclusions (
    profile_id VARCHAR(36) NOT NULL,
    ingredient_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_eating_preference_hard_exclusions PRIMARY KEY (profile_id, ingredient_id),
    CONSTRAINT fk_eating_preference_hard_exclusions_profile FOREIGN KEY (profile_id)
        REFERENCES app.eating_preferences(profile_id),
    CONSTRAINT fk_eating_preference_hard_exclusions_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id)
);

-- Nguyên liệu không thích — ưu tiên MỀM, hạ điểm chứ không loại thẳng.
CREATE TABLE app.eating_preference_disliked_ingredients (
    profile_id VARCHAR(36) NOT NULL,
    ingredient_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_eating_preference_disliked_ingredients PRIMARY KEY (profile_id, ingredient_id),
    CONSTRAINT fk_eating_preference_disliked_profile FOREIGN KEY (profile_id)
        REFERENCES app.eating_preferences(profile_id),
    CONSTRAINT fk_eating_preference_disliked_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id)
);

-- Dung nạp mùi và kết cấu ("tanh", "nhớt", "béo ngậy"...).
--
-- Cố ý là chuỗi tự do chứ không phải khoá ngoại: đây là lời người dùng tự mô tả cảm giác nghén,
-- không có danh mục chuẩn nào để tham chiếu, và ép nó vào một enum sẽ làm mất đúng phần thông tin
-- khiến gợi ý hợp khẩu vị hơn.
CREATE TABLE app.eating_preference_tolerance_tags (
    profile_id VARCHAR(36) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    CONSTRAINT pk_eating_preference_tolerance_tags PRIMARY KEY (profile_id, tag),
    CONSTRAINT fk_eating_preference_tolerance_tags_profile FOREIGN KEY (profile_id)
        REFERENCES app.eating_preferences(profile_id)
);

-- Loại bữa mặc định cần lập kế hoạch (bước 4). Bảng rỗng = chưa khai, lúc đó nhánh 5 dùng mặc
-- định của mình chứ không hiểu nhầm là "không cần bữa nào".
CREATE TABLE app.eating_preference_meal_types (
    profile_id VARCHAR(36) NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    CONSTRAINT pk_eating_preference_meal_types PRIMARY KEY (profile_id, meal_type),
    CONSTRAINT fk_eating_preference_meal_types_profile FOREIGN KEY (profile_id)
        REFERENCES app.eating_preferences(profile_id),
    CONSTRAINT ck_eating_preference_meal_types_meal_type CHECK (
        meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK'))
);

-- ---------------------------------------------------------------------------------------------
-- Sở thích món
-- ---------------------------------------------------------------------------------------------

-- Lựa chọn CHỦ ĐỘNG của người dùng với một món cụ thể (QĐ-03).
--
-- KHÔNG CÓ CỘT expires_at, dù đặc tả gốc có. Đặc tả cho cả bảng này lẫn app.meal_feedbacks (nhánh
-- 8) một cột expires_at và cả hai cùng ảnh hưởng điểm P — tức là cùng một ý niệm "hết hạn" nằm ở
-- hai chỗ. Đã chốt tách hẳn: bảng này là lựa chọn chủ động, sống đến khi người dùng tự đổi;
-- phản hồi CÓ HẠN sau bữa ăn thuộc app.meal_feedbacks. Thêm expires_at vào đây là làm sống lại
-- đúng chỗ trùng lặp đã bỏ.
--
-- BLOCKED là nút "Không muốn gợi ý món này", KHÔNG phải dị ứng: nó không bao giờ thay cho
-- app.user_allergens và không được dùng để suy ra người dùng dị ứng với thứ gì.
--
-- Khoá theo user_id chứ không theo profile_id (đúng đặc tả): "tôi không thích món này" không mất
-- đi khi mẹ bầu bắt đầu một thai kỳ mới.
CREATE TABLE app.user_recipe_preferences (
    user_id VARCHAR(36) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    preference_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Unique (user_id, recipe_id): một món chỉ mang đúng một thái độ, đổi FAVORITE sang BLOCKED
    -- là cập nhật dòng cũ chứ không thêm dòng thứ hai mâu thuẫn với dòng trước.
    CONSTRAINT pk_user_recipe_preferences PRIMARY KEY (user_id, recipe_id),
    CONSTRAINT fk_user_recipe_preferences_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT fk_user_recipe_preferences_recipe FOREIGN KEY (recipe_id)
        REFERENCES app.recipes(id),
    CONSTRAINT ck_user_recipe_preferences_type CHECK (
        preference_type IN ('FAVORITE', 'BLOCKED'))
);

-- ---------------------------------------------------------------------------------------------
-- Index
-- ---------------------------------------------------------------------------------------------

-- "Hồ sơ ăn uống của thai kỳ đang hoạt động của tôi" đi qua app.pregnancies, nên không cần index
-- theo user ở đây. Chiều thật sự cần là chiều ngược: nhánh 4 hỏi "nguyên liệu này bị ai loại" khi
-- danh mục seed đổi, và nhánh 10 hỏi "món vừa sửa nằm trong sở thích của ai".
CREATE INDEX ix_eating_preference_hard_exclusions_ingredient
    ON app.eating_preference_hard_exclusions(ingredient_id);
CREATE INDEX ix_eating_preference_disliked_ingredients_ingredient
    ON app.eating_preference_disliked_ingredients(ingredient_id);
CREATE INDEX ix_user_recipe_preferences_recipe
    ON app.user_recipe_preferences(recipe_id);
