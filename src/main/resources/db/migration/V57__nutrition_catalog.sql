-- Catalog món ăn của module dinh dưỡng mẹ bầu (docs/nutrition/branch-01-catalog-seed.md).
--
-- Toàn bộ 13 bảng dưới đây là DỮ LIỆU SEED, không phải dữ liệu người dùng: chúng được nạp từ
-- src/main/resources/nutrition/*.json lúc khởi động và không có màn quản trị nào ghi vào. Vì vậy
-- khoá chính dùng slug VARCHAR(64) do seed đặt (vd 'ing.tom-su', 'rec.com-ga-hap') thay cho UUID:
-- file JSON phải tham chiếu chéo lẫn nhau bằng tay, slug đọc được giúp người nhập liệu phát hiện
-- sai sót mà UUID thì không. Dữ liệu cá nhân của mẹ bầu ở các nhánh sau vẫn dùng VARCHAR(36) UUID
-- như phần còn lại của hệ thống.

-- ---------------------------------------------------------------------------------------------
-- Danh mục tham chiếu
-- ---------------------------------------------------------------------------------------------

-- Nguồn dữ liệu dinh dưỡng (NIH, USDA, Viện Dinh dưỡng...). Mỗi giá trị vi chất phải chỉ được về
-- một nguồn: khi bảo vệ đồ án cần trả lời "con số này lấy ở đâu ra".
CREATE TABLE app.nutrition_sources (
    id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    -- Mã bản ghi trong nguồn (vd FDC ID của USDA), để tra ngược đúng dòng dữ liệu.
    record_ref VARCHAR(255) NULL,
    retrieved_on DATE NULL,
    CONSTRAINT pk_nutrition_sources PRIMARY KEY (id)
);

-- Danh mục chất dinh dưỡng. Tồn tại để ĐƠN VỊ không bị nhập tuỳ ý trong từng món: mọi dòng
-- ingredient_nutrients phải khai đúng unit của chất tương ứng ở đây (ràng buộc kiểm tại loader,
-- xem NutritionSeedValidator). Thiếu bảng này thì 'mg' và 'mcg' sẽ lẫn nhau giữa các món.
CREATE TABLE app.nutrients (
    code VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    CONSTRAINT pk_nutrients PRIMARY KEY (code)
);

-- Thành phần gây dị ứng ở mức danh mục. Người dùng chọn từ đây (bảng app.user_allergens ở nhánh 3)
-- nên tên phải ổn định, không phụ thuộc cách viết của từng công thức.
CREATE TABLE app.allergens (
    id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_allergens PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------------------------
-- Nguyên liệu
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.ingredients (
    id VARCHAR(64) NOT NULL,
    canonical_name VARCHAR(255) NOT NULL,
    -- RAW / COOKED / DRIED / PROCESSED. Quyết định việc KHÔNG được gộp 100g gạo sống với 100g cơm
    -- chín ở danh sách mua sắm (nhánh 7) và việc có áp hệ số giữ lại hay không khi tính dinh dưỡng
    -- (nhánh 2). Đây là cột mang nghĩa nghiệp vụ, không phải nhãn mô tả.
    food_state VARCHAR(20) NOT NULL,
    food_group VARCHAR(30) NOT NULL,
    -- KNOWN = đã rà soát thành phần gây dị ứng (danh sách bên dưới có thể rỗng, nghĩa là "đã rà và
    -- không có"). UNKNOWN = CHƯA rà. Hai trạng thái này không được nhập làm một: món chứa nguyên
    -- liệu UNKNOWN bị loại khỏi thực đơn cá nhân hoá (BR03), còn KNOWN + rỗng thì được dùng.
    allergen_status VARCHAR(10) NOT NULL,
    source_id VARCHAR(64) NULL,
    CONSTRAINT pk_ingredients PRIMARY KEY (id),
    CONSTRAINT fk_ingredients_source FOREIGN KEY (source_id)
        REFERENCES app.nutrition_sources(id),
    CONSTRAINT ck_ingredients_food_state CHECK (
        food_state IN ('RAW', 'COOKED', 'DRIED', 'PROCESSED')),
    -- Nhóm này chính là nhóm gộp của danh sách mua sắm ở nhánh 7, nên để enum đóng: nhập sai
    -- chính tả sẽ đẻ ra một nhóm mồ côi trên màn hình đi chợ.
    CONSTRAINT ck_ingredients_food_group CHECK (
        food_group IN ('VEGETABLE_FRUIT', 'MEAT_FISH_EGG', 'GRAIN', 'DAIRY',
                       'LEGUME_NUT', 'SEASONING', 'OTHER')),
    CONSTRAINT ck_ingredients_allergen_status CHECK (
        allergen_status IN ('KNOWN', 'UNKNOWN'))
);

-- Tên địa phương của cùng một nguyên liệu ('tôm sú', 'tôm thẻ', 'tép'). Không có bảng này thì lọc
-- dị ứng sẽ SÓT khi công thức viết theo cách gọi vùng miền khác với canonical_name.
CREATE TABLE app.ingredient_aliases (
    ingredient_id VARCHAR(64) NOT NULL,
    alias VARCHAR(255) NOT NULL,
    CONSTRAINT pk_ingredient_aliases PRIMARY KEY (ingredient_id, alias),
    CONSTRAINT fk_ingredient_aliases_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id)
);

CREATE TABLE app.ingredient_allergens (
    ingredient_id VARCHAR(64) NOT NULL,
    allergen_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_ingredient_allergens PRIMARY KEY (ingredient_id, allergen_id),
    CONSTRAINT fk_ingredient_allergens_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id),
    CONSTRAINT fk_ingredient_allergens_allergen FOREIGN KEY (allergen_id)
        REFERENCES app.allergens(id)
);

-- Nguyên liệu con của gia vị và nước dùng hỗn hợp: 'nước mắm' chứa 'cá cơm', 'nước dùng' chứa
-- 'tôm khô'. EligibilityFilter (nhánh 4) phải duyệt ĐỆ QUY qua bảng này, nếu không món canh dùng
-- nước dùng có tôm khô sẽ lọt lưới với người dị ứng tôm (TC01).
CREATE TABLE app.ingredient_components (
    ingredient_id VARCHAR(64) NOT NULL,
    child_ingredient_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_ingredient_components PRIMARY KEY (ingredient_id, child_ingredient_id),
    CONSTRAINT fk_ingredient_components_parent FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id),
    CONSTRAINT fk_ingredient_components_child FOREIGN KEY (child_ingredient_id)
        REFERENCES app.ingredients(id),
    -- Tự chứa chính mình là vòng ngắn nhất, chặn luôn ở DB. Vòng dài hơn do loader phát hiện
    -- (duyệt DFS lúc nạp seed) vì CHECK không diễn đạt được quan hệ bắc cầu.
    CONSTRAINT ck_ingredient_components_not_self CHECK (ingredient_id <> child_ingredient_id)
);

-- Giá trị dinh dưỡng trên 100g phần ăn được.
CREATE TABLE app.ingredient_nutrients (
    ingredient_id VARCHAR(64) NOT NULL,
    nutrient_code VARCHAR(32) NOT NULL,
    -- CỐ Ý CHO PHÉP NULL. Thiếu dữ liệu KHÁC với bằng 0 (BR08): một nguyên liệu chưa đo folate
    -- không có nghĩa nó không chứa folate. Mọi tổng chứa giá trị NULL phải mang coverage PARTIAL
    -- và giao diện ghi "chưa đủ dữ liệu" thay vì tô đầy thanh tiến trình. Tuyệt đối không
    -- DEFAULT 0 cột này.
    amount_per_100g NUMERIC(12, 4) NULL,
    unit VARCHAR(16) NOT NULL,
    -- MEASURED = có đo; ESTIMATED = suy từ nguyên liệu tương tự; MISSING = chưa có dữ liệu
    -- (đi kèm amount_per_100g NULL).
    status VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NULL,
    CONSTRAINT pk_ingredient_nutrients PRIMARY KEY (ingredient_id, nutrient_code),
    CONSTRAINT fk_ingredient_nutrients_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id),
    CONSTRAINT fk_ingredient_nutrients_nutrient FOREIGN KEY (nutrient_code)
        REFERENCES app.nutrients(code),
    CONSTRAINT fk_ingredient_nutrients_source FOREIGN KEY (source_id)
        REFERENCES app.nutrition_sources(id),
    CONSTRAINT ck_ingredient_nutrients_status CHECK (
        status IN ('MEASURED', 'ESTIMATED', 'MISSING')),
    CONSTRAINT ck_ingredient_nutrients_amount CHECK (
        amount_per_100g IS NULL OR amount_per_100g >= 0),
    -- Giữ hai cột không nói ngược nhau: MISSING thì phải không có số, có số thì không được MISSING.
    CONSTRAINT ck_ingredient_nutrients_missing CHECK (
        (status = 'MISSING' AND amount_per_100g IS NULL)
        OR (status <> 'MISSING' AND amount_per_100g IS NOT NULL))
);

-- Giá ƯỚC TÍNH theo VND (BR18). estimated_at bắt buộc để màn hình nói được "giá tham khảo tháng
-- 10/2026" thay vì ngụ ý đây là báo giá hiện tại của cửa hàng.
CREATE TABLE app.price_estimates (
    ingredient_id VARCHAR(64) NOT NULL,
    price_per_base_unit_vnd NUMERIC(12, 2) NOT NULL,
    base_unit VARCHAR(16) NOT NULL,
    estimated_at DATE NOT NULL,
    source_note VARCHAR(255) NULL,
    CONSTRAINT pk_price_estimates PRIMARY KEY (ingredient_id),
    CONSTRAINT fk_price_estimates_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id),
    CONSTRAINT ck_price_estimates_price CHECK (price_per_base_unit_vnd >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Công thức
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.recipes (
    id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    -- Tăng khi seed sửa công thức. Nhật ký và mục kế hoạch ở các nhánh sau lưu kèm số này trong
    -- snapshot, nhờ đó sửa seed không viết lại lịch sử đã ăn của người dùng (BR17, TC21).
    version INT NOT NULL DEFAULT 1,
    -- MAIN_DISH (món hoàn chỉnh), STAPLE (tinh bột), PROTEIN (món mặn), VEGETABLE (rau),
    -- SIDE (ăn kèm), SNACK (bữa phụ). Món ăn kèm KHÔNG được tranh thứ hạng với món hoàn chỉnh
    -- khi ghép bữa (nhánh 4), nên vai trò là cột lọc chứ không phải nhãn hiển thị.
    role VARCHAR(20) NOT NULL,
    -- Công thức này nấu ra bao nhiêu phần. Mọi phép chia "dinh dưỡng một phần" đều dùng số này,
    -- nên nó phải > 0 (nhánh 2).
    servings_yield NUMERIC(6, 2) NOT NULL,
    -- Một phần nặng bao nhiêu gram sau khi nấu. NULL khi chưa cân được: lúc đó không cho người
    -- dùng chọn khẩu phần theo gram, chỉ cho chọn theo số phần.
    serving_gram NUMERIC(8, 2) NULL,
    minutes INT NOT NULL,
    steps TEXT NOT NULL,
    safety_notes TEXT NULL,
    -- Object key trong bucket public media (cùng chỗ ảnh bài viết), KHÔNG phải URL đầy đủ: đổi
    -- domain CDN thì không phải migrate dữ liệu. DTO tự dựng image_url lúc trả về.
    image_key VARCHAR(255) NULL,
    -- Nguyên liệu chính, phục vụ điểm đa dạng V ở nhánh 4 ("hôm qua đã ăn thịt gà rồi").
    -- KHÔNG dùng cho phát hiện dị ứng — việc đó đi qua ingredient_allergens và đệ quy component.
    main_ingredient_tag VARCHAR(64) NULL,
    CONSTRAINT pk_recipes PRIMARY KEY (id),
    CONSTRAINT ck_recipes_role CHECK (
        role IN ('MAIN_DISH', 'STAPLE', 'PROTEIN', 'VEGETABLE', 'SIDE', 'SNACK')),
    CONSTRAINT ck_recipes_servings_yield CHECK (servings_yield > 0),
    CONSTRAINT ck_recipes_serving_gram CHECK (serving_gram IS NULL OR serving_gram > 0),
    CONSTRAINT ck_recipes_minutes CHECK (minutes > 0),
    CONSTRAINT ck_recipes_version CHECK (version >= 1)
);

-- Một công thức dùng được cho nhiều loại bữa (cháo ăn được cả sáng lẫn phụ).
CREATE TABLE app.recipe_meal_types (
    recipe_id VARCHAR(64) NOT NULL,
    meal_type VARCHAR(20) NOT NULL,
    CONSTRAINT pk_recipe_meal_types PRIMARY KEY (recipe_id, meal_type),
    CONSTRAINT fk_recipe_meal_types_recipe FOREIGN KEY (recipe_id)
        REFERENCES app.recipes(id),
    CONSTRAINT ck_recipe_meal_types_meal_type CHECK (
        meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK'))
);

CREATE TABLE app.recipe_ingredients (
    recipe_id VARCHAR(64) NOT NULL,
    ingredient_id VARCHAR(64) NOT NULL,
    -- Gram ĐÚNG TRẠNG THÁI của bản ghi dinh dưỡng được tham chiếu. Không quy đổi thìa/ml sang gram
    -- bằng một mật độ chung (nhánh 2).
    quantity_gram NUMERIC(10, 2) NOT NULL,
    preparation_state VARCHAR(20) NOT NULL,
    CONSTRAINT pk_recipe_ingredients PRIMARY KEY (recipe_id, ingredient_id),
    CONSTRAINT fk_recipe_ingredients_recipe FOREIGN KEY (recipe_id)
        REFERENCES app.recipes(id),
    CONSTRAINT fk_recipe_ingredients_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id),
    CONSTRAINT ck_recipe_ingredients_prep_state CHECK (
        preparation_state IN ('RAW', 'COOKED', 'DRIED', 'PROCESSED')),
    CONSTRAINT ck_recipe_ingredients_quantity CHECK (quantity_gram >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Template bữa
-- ---------------------------------------------------------------------------------------------

-- Cấu trúc một bữa: hoặc một món hoàn chỉnh (SINGLE_DISH), hoặc một tập vai trò (ROLE_SET).
-- Không trộn ngẫu nhiên (BR06). PlanGenerator ở nhánh 5 tạo vị trí theo bảng này TRƯỚC khi chọn món.
CREATE TABLE app.meal_templates (
    meal_type VARCHAR(20) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    -- Tỉ lệ năng lượng tham khảo của bữa trong ngày (0.25 = 25%). Là giả định kỹ thuật dùng để
    -- xếp hạng, KHÔNG phải quy tắc y khoa.
    role_target_share NUMERIC(4, 3) NOT NULL,
    default_serving_options VARCHAR(100) NOT NULL,
    CONSTRAINT pk_meal_templates PRIMARY KEY (meal_type, mode),
    CONSTRAINT ck_meal_templates_meal_type CHECK (
        meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK')),
    CONSTRAINT ck_meal_templates_mode CHECK (mode IN ('SINGLE_DISH', 'ROLE_SET')),
    CONSTRAINT ck_meal_templates_share CHECK (
        role_target_share > 0 AND role_target_share <= 1)
);

CREATE TABLE app.meal_template_roles (
    meal_type VARCHAR(20) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    role VARCHAR(20) NOT NULL,
    CONSTRAINT pk_meal_template_roles PRIMARY KEY (meal_type, mode, role),
    CONSTRAINT fk_meal_template_roles_template FOREIGN KEY (meal_type, mode)
        REFERENCES app.meal_templates(meal_type, mode),
    CONSTRAINT ck_meal_template_roles_role CHECK (
        role IN ('MAIN_DISH', 'STAPLE', 'PROTEIN', 'VEGETABLE', 'SIDE', 'SNACK'))
);

-- ---------------------------------------------------------------------------------------------
-- Trạng thái nạp seed
-- ---------------------------------------------------------------------------------------------

-- Một dòng duy nhất, ghi rules_version của tập seed đang nằm trong database. Loader so sánh với
-- rules_version trong nutritionRules.json để biết có phải nạp lại hay không — nếu không có bảng
-- này thì mỗi lần khởi động đều phải ghi đè toàn bộ danh mục.
CREATE TABLE app.nutrition_seed_state (
    id VARCHAR(1) NOT NULL,
    rules_version VARCHAR(64) NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_nutrition_seed_state PRIMARY KEY (id),
    -- Khoá dòng đơn: chỉ chấp nhận đúng một dòng với id = 'X'.
    CONSTRAINT ck_nutrition_seed_state_single_row CHECK (id = 'X')
);

-- ---------------------------------------------------------------------------------------------
-- Index
-- ---------------------------------------------------------------------------------------------

-- Khớp truy vấn tra cứu chính của GET /api/v1/recipes: lọc theo vai trò và thời gian nấu, sắp
-- theo id để phân trang con trỏ ổn định.
CREATE INDEX ix_recipes_role_minutes ON app.recipes(role, minutes, id);

-- Lọc theo loại bữa đi qua bảng nối, nên cần chiều ngược lại (meal_type -> recipe).
CREATE INDEX ix_recipe_meal_types_meal_type ON app.recipe_meal_types(meal_type, recipe_id);

-- EligibilityFilter duyệt ngược "nguyên liệu này nằm trong món nào".
CREATE INDEX ix_recipe_ingredients_ingredient ON app.recipe_ingredients(ingredient_id);

-- Duyệt đệ quy nguyên liệu con theo chiều cha -> con.
CREATE INDEX ix_ingredient_components_child ON app.ingredient_components(child_ingredient_id);

-- Tra "nguyên liệu nào chứa thành phần dị ứng X" khi người dùng đổi hồ sơ.
CREATE INDEX ix_ingredient_allergens_allergen ON app.ingredient_allergens(allergen_id);

-- Ánh xạ tên địa phương về nguyên liệu chuẩn lúc nhập seed và lúc tìm kiếm.
CREATE INDEX ix_ingredient_aliases_alias ON app.ingredient_aliases(alias);
