-- Danh sách mua sắm: nguyên liệu của một thực đơn, gộp theo danh tính VÀ trạng thái chế biến
-- (docs/nutrition/branch-07-shopping-list.md).
--
-- Số V63 lấy từ chính database dùng chung lúc bắt đầu nhánh (SELECT MAX(version::numeric) FROM
-- app.flyway_schema_history trả 62) — xem docs/nutrition/_CONTRACT.md mục 2. Kiểm lại con số này
-- ngay trước khi merge: người khác có thể đã chiếm số trong lúc nhánh này đang làm.
--
-- Hai bảng, hai vai trò tách bạch:
--   shopping_lists       — một danh sách THUỘC VỀ một kế hoạch và một phạm vi ngày
--   shopping_list_items  — một dòng đi chợ: lượng cần (dẫn xuất) + trạng thái của người dùng (bền)
--
-- Lượng cần là HÀM THUẦN của các mục kế hoạch, nên nó được dựng lại từ kế hoạch mỗi lần đồng bộ.
-- Thứ KHÔNG dựng lại được là thao tác của người dùng: đã có bao nhiêu, đã mua bao nhiêu, dòng tự
-- nhập. Đó mới là lý do hai bảng này tồn tại — nếu chỉ cần lượng cần thì một hàm tính là đủ.

-- ---------------------------------------------------------------------------------------------
-- Danh sách
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.shopping_lists (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    plan_id VARCHAR(36) NOT NULL,

    -- plan_version của lần đồng bộ gần nhất. Bằng plan_version của kế hoạch nghĩa là các dòng đang
    -- khớp thực đơn; lệch nghĩa là kế hoạch đã đổi và lần đọc tới phải tính lại lượng cần. Dùng
    -- đúng con số của kế hoạch thay vì đếm một phiên bản thứ hai: danh sách mua sắm đổi đúng khi và
    -- chỉ khi các mục kế hoạch đổi, nên hai số đếm riêng chỉ tạo cơ hội lệch nhau.
    plan_version INT NOT NULL,

    -- Phạm vi ngày người dùng chọn. Hai cột chứ không phải một kiểu daterange: daterange chỉ có ở
    -- PostgreSQL, còn bài kiểm migration chạy trên H2 ở chế độ PostgreSQL — và V60 đã tách
    -- start_date/end_date đúng cách này cho meal_plans, nên hai bảng so khớp phạm vi mà không phải
    -- đổi kiểu dữ liệu giữa đường.
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_shopping_lists PRIMARY KEY (id),
    CONSTRAINT fk_shopping_lists_user FOREIGN KEY (user_id) REFERENCES app.users(id),
    -- Xóa kế hoạch thì danh sách đi theo: một danh sách không còn thực đơn nào ở sau lưng thì
    -- không có cách nào tính lại lượng cần, và một danh sách không tính lại được là một danh sách
    -- sai ngay lần đổi món kế tiếp.
    CONSTRAINT fk_shopping_lists_plan FOREIGN KEY (plan_id)
        REFERENCES app.meal_plans(id) ON DELETE CASCADE,
    -- Mỗi (kế hoạch, phạm vi ngày) đúng một danh sách: mở lại màn hình mua sắm phải thấy lại đúng
    -- những ô đã tick, chứ không phải một danh sách trắng thứ hai.
    CONSTRAINT uq_shopping_lists_plan_range UNIQUE (plan_id, start_date, end_date),
    CONSTRAINT ck_shopping_lists_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_shopping_lists_plan_version CHECK (plan_version >= 1)
);

-- ---------------------------------------------------------------------------------------------
-- Dòng đi chợ
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.shopping_list_items (
    id VARCHAR(36) NOT NULL,
    list_id VARCHAR(36) NOT NULL,

    -- NULL = DÒNG TỰ NHẬP. Người dùng thêm thứ cần mua cho nhu cầu khác (giấy, sữa tắm, trái cây
    -- ngoài thực đơn). Dòng đó KHÔNG ảnh hưởng dinh dưỡng và không bao giờ được quy về một nguyên
    -- liệu: nó không có bảng dinh dưỡng, không có giá, không tham gia phép tính nào của thực đơn.
    ingredient_id VARCHAR(64) NULL,

    -- Trạng thái chế biến của dòng. Đây là MỘT NỬA CỦA KHÓA GỘP, không phải nhãn mô tả: 100 g gạo
    -- sống và 100 g cơm chín là hai dòng, và ràng buộc duy nhất bên dưới là chỗ ép điều đó
    -- (TC10). Cộng chúng thành "200 g" cho ra một con số không mua được ở bất kỳ quầy nào.
    food_state VARCHAR(20) NULL,
    -- Nhóm hàng để xếp dòng theo quầy: rau quả · thịt cá trứng · ngũ cốc · ...
    food_group VARCHAR(30) NULL,
    -- Tên hiển thị chụp lúc đồng bộ. Nhờ nó một dòng đã mua vẫn đọc được sau khi món dùng nguyên
    -- liệu đó biến khỏi thực đơn — lúc ấy không còn snapshot nào trong kế hoạch để tra tên.
    display_name VARCHAR(255) NULL,

    -- NEEDED (mặc định) · HAVE (đã có ở nhà) · PURCHASED (đã mua).
    state VARCHAR(20) NOT NULL,

    -- Lượng cần của lần đồng bộ gần nhất, theo unit. NULL ở dòng tự nhập khi người dùng không ghi
    -- số lượng — "mua ít trái cây" là một dòng hợp lệ.
    quantity NUMERIC(10, 2) NULL,
    -- Đơn vị của quantity/have_quantity/purchased_quantity. Dòng nguyên liệu luôn là 'g' vì công
    -- thức ghi gram; CHỈ những đơn vị quy đổi được mới nhận ở đầu vào (xem MassUnits) — không tự
    -- đoán một thìa là bao nhiêu gram.
    unit VARCHAR(16) NULL,

    -- Người dùng khai đã có sẵn bao nhiêu / đã mua bao nhiêu. Hai con số tách nhau vì chúng trả lời
    -- hai câu hỏi khác nhau: "nhà còn gạo" khác "đã mua gạo hôm nay", và lượng còn cần là phần dôi
    -- ra sau khi trừ cả hai.
    have_quantity NUMERIC(10, 2) NULL,
    purchased_quantity NUMERIC(10, 2) NULL,

    -- Nội dung dòng tự nhập; NULL ở dòng nguyên liệu.
    custom_text VARCHAR(255) NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_shopping_list_items PRIMARY KEY (id),
    CONSTRAINT fk_shopping_list_items_list FOREIGN KEY (list_id)
        REFERENCES app.shopping_lists(id) ON DELETE CASCADE,
    CONSTRAINT fk_shopping_list_items_ingredient FOREIGN KEY (ingredient_id)
        REFERENCES app.ingredients(id),
    CONSTRAINT ck_shopping_list_items_state CHECK (
        state IN ('NEEDED', 'HAVE', 'PURCHASED')),
    -- Hai dạng dòng, không có dạng thứ ba: dòng nguyên liệu có mã nguyên liệu và trạng thái chế
    -- biến, dòng tự nhập có chữ người dùng gõ. Một dòng vừa trỏ tới nguyên liệu vừa mang chữ tự do
    -- sẽ mập mờ ở đúng chỗ nguy hiểm nhất — nó có tính vào dinh dưỡng hay không.
    CONSTRAINT ck_shopping_list_items_shape CHECK (
        (ingredient_id IS NOT NULL AND food_state IS NOT NULL AND custom_text IS NULL)
        OR (ingredient_id IS NULL AND food_state IS NULL AND custom_text IS NOT NULL)),
    CONSTRAINT ck_shopping_list_items_quantity CHECK (quantity IS NULL OR quantity >= 0),
    CONSTRAINT ck_shopping_list_items_have CHECK (have_quantity IS NULL OR have_quantity >= 0),
    CONSTRAINT ck_shopping_list_items_purchased CHECK (
        purchased_quantity IS NULL OR purchased_quantity >= 0),
    -- Khóa gộp: danh tính VÀ trạng thái tương thích. Gạo sống và cơm chín, hay cùng một nguyên liệu
    -- ở hai trạng thái, đều đi qua được ràng buộc này vì food_state khác nhau — và chính ràng buộc
    -- này chặn một lỗi lập trình ở lớp trên gộp chúng làm một.
    --
    -- ingredient_id NULL (dòng tự nhập) không bị ràng buộc này chạm tới: NULL không bằng NULL, nên
    -- người dùng thêm bao nhiêu dòng tự nhập cũng được.
    CONSTRAINT uq_shopping_list_items_identity UNIQUE (list_id, ingredient_id, food_state)
);

-- ---------------------------------------------------------------------------------------------
-- Index
-- ---------------------------------------------------------------------------------------------

-- "Danh sách mua sắm của thực đơn này" — lối vào duy nhất của màn hình mua sắm.
CREATE INDEX ix_shopping_lists_plan ON app.shopping_lists(plan_id);
-- "Danh sách của tôi", cho màn hình tổng hợp về sau và cho kiểm tra chủ sở hữu.
CREATE INDEX ix_shopping_lists_user ON app.shopping_lists(user_id, start_date DESC);
-- Nạp cả danh sách một lượt.
CREATE INDEX ix_shopping_list_items_list ON app.shopping_list_items(list_id);
