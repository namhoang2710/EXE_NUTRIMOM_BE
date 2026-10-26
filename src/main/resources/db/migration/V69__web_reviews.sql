-- Đánh giá web (25/10/2026). V68 thuộc nhánh feature/forgot-password-email (password_reset_tokens), nên nhánh
-- này phải merge SAU V68: Flyway đang tắt out-of-order, áp V69 trước V68 thì V68 sẽ bị bỏ qua im lặng.
--
-- Mỗi người một đánh giá còn hiệu lực; trả lời của đội NutriMom nằm luôn trên dòng (mỗi đánh giá đúng một câu).
CREATE TABLE app.web_reviews (
    id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    rating SMALLINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    hidden_reason VARCHAR(255) NULL,
    hidden_by VARCHAR(36) NULL,
    hidden_at TIMESTAMP WITH TIME ZONE NULL,
    reply_content VARCHAR(1000) NULL,
    reply_by VARCHAR(36) NULL,
    replied_at TIMESTAMP WITH TIME ZONE NULL,
    reply_updated_at TIMESTAMP WITH TIME ZONE NULL,
    edited_after_reply BOOLEAN NOT NULL DEFAULT FALSE,
    edited_at TIMESTAMP WITH TIME ZONE NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_web_reviews PRIMARY KEY (id),
    CONSTRAINT fk_web_reviews_author FOREIGN KEY (author_id) REFERENCES app.users(id),
    CONSTRAINT fk_web_reviews_hidden_by FOREIGN KEY (hidden_by) REFERENCES app.users(id),
    CONSTRAINT fk_web_reviews_reply_by FOREIGN KEY (reply_by) REFERENCES app.users(id),
    CONSTRAINT ck_web_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_web_reviews_status CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    -- Xoá mềm luôn đi cùng trạng thái DELETED, để không có dòng "đã xoá" mà vẫn hiện công khai.
    CONSTRAINT ck_web_reviews_deleted CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

-- Một người một đánh giá còn hiệu lực; xoá mềm rồi thì viết lại được. Service cũng khoá dòng user để chặn
-- trước, index này là chốt cuối khi hai request tạo cùng lúc.
CREATE UNIQUE INDEX ux_web_reviews_author_active ON app.web_reviews(author_id) WHERE deleted_at IS NULL;
-- Trang công khai: lọc PUBLISHED, keyset (created_at DESC, id DESC), có thể lọc theo số sao.
CREATE INDEX ix_web_reviews_public ON app.web_reviews(status, created_at DESC, id DESC);
CREATE INDEX ix_web_reviews_public_rating ON app.web_reviews(status, rating, created_at DESC, id DESC);
