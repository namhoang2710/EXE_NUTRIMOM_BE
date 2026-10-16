-- Nhật ký gợi ý món (docs/nutrition/branch-04-recommendation.md).
--
-- Số V59 lấy từ chính database dùng chung lúc bắt đầu nhánh (`SELECT MAX(version::numeric) FROM
-- app.flyway_schema_history` trả 58) — xem docs/nutrition/_CONTRACT.md mục 2. Kiểm lại con số này
-- ngay trước khi merge.
--
-- Bảng này tồn tại để TRẢ LỜI ĐƯỢC CÂU "vì sao món này đứng trên món kia". Đây là phần chứng minh
-- của đồ án: mỗi lượt gợi ý lưu lại đủ đầu vào (phiên bản hồ sơ, phiên bản tập quy tắc, seed) và
-- đủ đầu ra từng phần (điểm N, P, V, B, C) để dựng lại y hệt thứ hạng về sau. Không có nó thì
-- không ai kiểm chứng được một lời giải thích đã hiển thị cho người dùng.

-- ---------------------------------------------------------------------------------------------
-- Đầu vào của một lượt gợi ý
-- ---------------------------------------------------------------------------------------------

CREATE TABLE app.recommendation_logs (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,

    -- Mã tương quan do client gửi, CHỈ để tra ngược một lượt gọi khi đi tìm lỗi.
    --
    -- KHÔNG unique và KHÔNG phải idempotency key: _CONTRACT.md mục 6 giới hạn idempotency cho hai
    -- thao tác GHI (tạo bản xem trước, tạo nhật ký ăn). Gợi ý là thao tác đọc, gọi lại hai lần
    -- không sinh ra thứ gì cần chống trùng — thứ cần tái lập là KẾT QUẢ, và việc đó do bộ ba
    -- (profile_version, rules_version, seed) bên dưới lo.
    request_id VARCHAR(64) NULL,

    -- Bộ ba tái lập. Cùng ba giá trị này trên cùng tập seed sẽ cho đúng thứ hạng cũ.
    profile_version INT NOT NULL,
    rules_version VARCHAR(64) NOT NULL,
    -- Seed của nút "Tạo lại": đổi seed thì thứ tự các món BẰNG ĐIỂM đổi theo, nhưng đổi có thể
    -- tái lập chứ không phải ngẫu nhiên mỗi lần gọi.
    seed BIGINT NOT NULL,

    meal_type VARCHAR(20) NOT NULL,
    -- READY / NO_MATCH. Đây là kết quả NGHIỆP VỤ, không phải lỗi: hết món hợp lệ vẫn trả HTTP 200
    -- kèm lý do (_CONTRACT.md mục 3).
    status VARCHAR(20) NOT NULL,
    -- Mã lý do ở mức cả lượt gợi ý (vd NO_MATCH, SEARCH_LIMIT), phân tách bằng dấu phẩy.
    reason_codes VARCHAR(255) NOT NULL DEFAULT '',
    -- Những tiêu chí thực sự tham gia phép tổng lần này, vd 'N,P,V,C' khi người dùng bỏ qua ngân
    -- sách. Thiếu cột này thì không giải thích được vì sao cùng một món lại có hai điểm khác nhau
    -- ở hai lượt gọi.
    criteria_used VARCHAR(64) NOT NULL DEFAULT '',

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_recommendation_logs PRIMARY KEY (id),
    CONSTRAINT fk_recommendation_logs_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_recommendation_logs_status CHECK (status IN ('READY', 'NO_MATCH')),
    CONSTRAINT ck_recommendation_logs_meal_type CHECK (
        meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK')),
    CONSTRAINT ck_recommendation_logs_profile_version CHECK (profile_version >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Từng món ứng viên của lượt đó
-- ---------------------------------------------------------------------------------------------

-- Một dòng cho MỖI món ứng viên, kể cả món bị loại.
--
-- Cố ý là bảng con chứ không phải ba cột JSON như đặc tả phác ra (`candidates`, `feature_scores`,
-- `reason_codes`): "ghi đủ feature_scores để tái lập xếp hạng" chỉ có nghĩa khi truy vấn được —
-- câu hỏi thật khi bảo vệ đồ án là "món bị loại vì gì" và "điểm P của món này từ đâu ra", và cả
-- hai đều là một câu SELECT trên bảng này. Nhét JSON vào một cột thì phải mở từng dòng ra đọc tay.
--
-- Món BỊ LOẠI cũng phải có dòng. Chỉ ghi món thắng thì mất đúng nửa phần giải thích: người dùng
-- hỏi "sao không thấy món cá hồi" và không ai trả lời được.
CREATE TABLE app.recommendation_log_entries (
    log_id VARCHAR(36) NOT NULL,
    recipe_id VARCHAR(64) NOT NULL,
    -- Phiên bản công thức TẠI LÚC chấm điểm. Seed sửa món về sau thì dòng log này vẫn nói đúng
    -- thứ đã được chấm (BR17).
    recipe_version INT NOT NULL,

    -- ELIGIBLE / BLOCKED / UNKNOWN. UNKNOWN là "chưa rõ thành phần" (BR03), khác hẳn BLOCKED là
    -- "đã biết và vi phạm" — gộp hai cái làm một thì mất khả năng nói với người dùng rằng món
    -- vắng mặt vì DỮ LIỆU CÒN THIẾU chứ không phải vì không hợp với họ.
    eligibility VARCHAR(20) NOT NULL,
    -- Thứ hạng sau khi xếp, bắt đầu từ 1. NULL cho món không qua được bước lọc: món chưa qua lọc
    -- KHÔNG BAO GIỜ được chấm điểm (bất biến 2), nên nó không có thứ hạng để ghi.
    rank SMALLINT NULL,

    -- Năm thành phần điểm, thang 0–100. NULL nghĩa là tiêu chí đó KHÔNG DÙNG ĐƯỢC cho lượt này
    -- (người dùng bỏ qua ngân sách, món chưa có giá, chưa có dữ liệu vi chất...) và trọng số của
    -- nó đã bị loại khỏi phép tổng. NULL ở đây tuyệt đối không phải 0 điểm — đó là khác biệt giữa
    -- "không biết" và "kém", và chấm 0 cho cái không biết sẽ đẩy mọi món thiếu dữ liệu xuống đáy.
    score_nutrition NUMERIC(6, 3) NULL,
    score_preference NUMERIC(6, 3) NULL,
    score_variety NUMERIC(6, 3) NULL,
    score_budget NUMERIC(6, 3) NULL,
    score_convenience NUMERIC(6, 3) NULL,
    -- Tổng đã chuẩn hoá theo đúng tập tiêu chí dùng được. NULL khi món không được chấm.
    score_total NUMERIC(6, 3) NULL,

    -- COMPLETE / PARTIAL — độ đầy đủ của DỮ LIỆU vi chất, không phải chất lượng của món.
    nutrition_status VARCHAR(20) NOT NULL,
    -- Mã lý do của riêng món này, phân tách bằng dấu phẩy: vì sao bị loại, hoặc vì sao được gợi ý.
    reason_codes VARCHAR(255) NOT NULL DEFAULT '',

    CONSTRAINT pk_recommendation_log_entries PRIMARY KEY (log_id, recipe_id),
    CONSTRAINT fk_recommendation_log_entries_log FOREIGN KEY (log_id)
        REFERENCES app.recommendation_logs(id),
    CONSTRAINT fk_recommendation_log_entries_recipe FOREIGN KEY (recipe_id)
        REFERENCES app.recipes(id),
    CONSTRAINT ck_recommendation_log_entries_eligibility CHECK (
        eligibility IN ('ELIGIBLE', 'BLOCKED', 'UNKNOWN')),
    CONSTRAINT ck_recommendation_log_entries_nutrition_status CHECK (
        nutrition_status IN ('COMPLETE', 'PARTIAL')),
    -- Khoá chặt bất biến 2 ở tầng database: món không ELIGIBLE thì không được mang điểm tổng.
    -- Một lỗi lập trình làm điểm rò sang món bị loại sẽ dừng ở đây chứ không lặng lẽ vào log rồi
    -- thành bằng chứng sai lúc bảo vệ.
    CONSTRAINT ck_recommendation_log_entries_scored_only_when_eligible CHECK (
        eligibility = 'ELIGIBLE' OR (score_total IS NULL AND rank IS NULL))
);

-- ---------------------------------------------------------------------------------------------
-- Index
-- ---------------------------------------------------------------------------------------------

-- "Lượt gợi ý gần đây của người này" — lối vào duy nhất khi đi tìm lỗi từ một báo cáo của người dùng.
CREATE INDEX ix_recommendation_logs_user_created
    ON app.recommendation_logs(user_id, created_at DESC);

-- Tra ngược từ mã tương quan client gửi kèm.
CREATE INDEX ix_recommendation_logs_request
    ON app.recommendation_logs(user_id, request_id);

-- "Món này đã bị loại bao nhiêu lần và vì sao" — câu hỏi khi rà chất lượng seed.
CREATE INDEX ix_recommendation_log_entries_recipe
    ON app.recommendation_log_entries(recipe_id);
