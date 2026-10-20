-- Cộng đồng dành cho mẹ: bài viết, ảnh, bình luận, tim, lưu, báo cáo và thông báo riêng.
--
-- Số V65: repo và database dùng chung cùng dừng ở V64 (kiểm flyway_schema_history ngày 20/10/2026),
-- không nhánh remote nào có V65+. Kiểm lại ngay trước khi merge.
--
-- Chỉ có MỘT cộng đồng nên không có bảng communities/groups. Không có cột video, không có cờ ẩn danh:
-- mọi bài đều gắn author_id thật.
--
-- Không dùng partial index để file chạy được cả trên H2 (CommunityMigrationTest).

-- ---------------------------------------------------------------------------------------------
-- Bài viết
-- ---------------------------------------------------------------------------------------------
-- like_count/comment_count lưu sẵn để feed không phải COUNT từng bài; chỉ đổi bằng câu UPDATE
-- cộng/trừ nguyên tử trong cùng transaction với dòng tim/bình luận.
-- published_at = lần duyệt đầu tiên; sửa bài rồi duyệt lại không đẩy bài lên đầu feed.
CREATE TABLE app.community_posts (
    id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    topic VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reject_reason VARCHAR(500) NULL,
    published_at TIMESTAMP WITH TIME ZONE NULL,
    moderated_by VARCHAR(36) NULL,
    moderated_at TIMESTAMP WITH TIME ZONE NULL,
    pinned_at TIMESTAMP WITH TIME ZONE NULL,
    pinned_by VARCHAR(36) NULL,
    like_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    image_count INT NOT NULL DEFAULT 0,
    edited_at TIMESTAMP WITH TIME ZONE NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_posts PRIMARY KEY (id),
    CONSTRAINT fk_community_posts_author FOREIGN KEY (author_id) REFERENCES app.users(id),
    CONSTRAINT fk_community_posts_moderator FOREIGN KEY (moderated_by) REFERENCES app.users(id),
    CONSTRAINT fk_community_posts_pinner FOREIGN KEY (pinned_by) REFERENCES app.users(id),
    CONSTRAINT ck_community_posts_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REMOVED')),
    CONSTRAINT ck_community_posts_topic CHECK (topic IN ('PREGNANCY', 'NUTRITION', 'MOTHER_HEALTH', 'BABY_CARE', 'SHARING', 'OTHER')),
    CONSTRAINT ck_community_posts_counters CHECK (like_count >= 0 AND comment_count >= 0 AND image_count BETWEEN 0 AND 4),
    CONSTRAINT ck_community_posts_content CHECK (char_length(content) BETWEEN 1 AND 5000),
    -- Bài đang hiển thị trên feed phải có mốc xuất bản để cursor không gặp NULL.
    CONSTRAINT ck_community_posts_published CHECK (status <> 'APPROVED' OR published_at IS NOT NULL)
);
CREATE INDEX ix_community_posts_feed ON app.community_posts(status, published_at DESC, id DESC);
CREATE INDEX ix_community_posts_author ON app.community_posts(author_id, created_at DESC, id DESC);
CREATE INDEX ix_community_posts_queue ON app.community_posts(status, created_at DESC, id DESC);
CREATE INDEX ix_community_posts_pinned ON app.community_posts(pinned_at);

-- ---------------------------------------------------------------------------------------------
-- Ảnh
-- ---------------------------------------------------------------------------------------------
-- Upload trước, gắn vào bài sau. PENDING = chưa gắn (hết hạn sau delete_after), ATTACHED = thuộc
-- một bài, ORPHANED = bài bị xoá/ảnh bị bỏ khi sửa, chờ tới delete_after mới xoá trên R2.
-- post_id KHÔNG cascade: xoá ảnh là việc của job dọn dẹp, sau khi object R2 đã xoá xong.
CREATE TABLE app.community_post_images (
    id VARCHAR(36) NOT NULL,
    post_id VARCHAR(36) NULL,
    owner_id VARCHAR(36) NOT NULL,
    image_key VARCHAR(200) NOT NULL,
    image_url VARCHAR(2048) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    size_bytes INT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    delete_after TIMESTAMP WITH TIME ZONE NULL,
    cleanup_attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_post_images PRIMARY KEY (id),
    CONSTRAINT fk_community_post_images_post FOREIGN KEY (post_id) REFERENCES app.community_posts(id),
    CONSTRAINT fk_community_post_images_owner FOREIGN KEY (owner_id) REFERENCES app.users(id),
    CONSTRAINT ck_community_post_images_status CHECK (status IN ('PENDING', 'ATTACHED', 'ORPHANED')),
    CONSTRAINT ck_community_post_images_shape CHECK (
        (status = 'ATTACHED' AND post_id IS NOT NULL AND delete_after IS NULL)
        OR (status IN ('PENDING', 'ORPHANED') AND delete_after IS NOT NULL)),
    CONSTRAINT ck_community_post_images_size CHECK (width > 0 AND height > 0 AND size_bytes > 0 AND sort_order BETWEEN 0 AND 3)
);
CREATE UNIQUE INDEX ux_community_post_images_key ON app.community_post_images(image_key);
CREATE INDEX ix_community_post_images_post ON app.community_post_images(post_id, sort_order);
CREATE INDEX ix_community_post_images_cleanup ON app.community_post_images(status, delete_after);
CREATE INDEX ix_community_post_images_owner ON app.community_post_images(owner_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Bình luận (hiện ngay, xoá mềm để comment_count và thông báo cũ vẫn khớp)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE app.community_comments (
    id VARCHAR(36) NOT NULL,
    post_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NULL,
    deleted_by VARCHAR(36) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_comments PRIMARY KEY (id),
    CONSTRAINT fk_community_comments_post FOREIGN KEY (post_id) REFERENCES app.community_posts(id),
    CONSTRAINT fk_community_comments_author FOREIGN KEY (author_id) REFERENCES app.users(id),
    CONSTRAINT fk_community_comments_deleter FOREIGN KEY (deleted_by) REFERENCES app.users(id),
    CONSTRAINT ck_community_comments_content CHECK (char_length(content) BETWEEN 1 AND 1000)
);
CREATE INDEX ix_community_comments_post ON app.community_comments(post_id, created_at, id);
CREATE INDEX ix_community_comments_author ON app.community_comments(author_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Tim và lưu: khoá chính (post_id, user_id) là thứ chặn thao tác trùng, không phải code Java.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE app.community_post_likes (
    post_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_post_likes PRIMARY KEY (post_id, user_id),
    CONSTRAINT fk_community_post_likes_post FOREIGN KEY (post_id) REFERENCES app.community_posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_post_likes_user FOREIGN KEY (user_id) REFERENCES app.users(id) ON DELETE CASCADE
);
CREATE INDEX ix_community_post_likes_user ON app.community_post_likes(user_id, post_id);

CREATE TABLE app.community_post_saves (
    post_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_post_saves PRIMARY KEY (post_id, user_id),
    CONSTRAINT fk_community_post_saves_post FOREIGN KEY (post_id) REFERENCES app.community_posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_post_saves_user FOREIGN KEY (user_id) REFERENCES app.users(id) ON DELETE CASCADE
);
CREATE INDEX ix_community_post_saves_user ON app.community_post_saves(user_id, created_at DESC, post_id DESC);

-- ---------------------------------------------------------------------------------------------
-- Báo cáo: mỗi người báo cáo một bài đúng một lần (ux_community_reports_post_reporter).
-- reporter_id chỉ được đọc qua API admin.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE app.community_reports (
    id VARCHAR(36) NOT NULL,
    post_id VARCHAR(36) NOT NULL,
    reporter_id VARCHAR(36) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    note VARCHAR(2000) NULL,
    status VARCHAR(20) NOT NULL,
    handled_by VARCHAR(36) NULL,
    handled_at TIMESTAMP WITH TIME ZONE NULL,
    resolution_note VARCHAR(2000) NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_reports PRIMARY KEY (id),
    CONSTRAINT fk_community_reports_post FOREIGN KEY (post_id) REFERENCES app.community_posts(id),
    CONSTRAINT fk_community_reports_reporter FOREIGN KEY (reporter_id) REFERENCES app.users(id),
    CONSTRAINT fk_community_reports_handler FOREIGN KEY (handled_by) REFERENCES app.users(id),
    CONSTRAINT ck_community_reports_reason CHECK (reason IN ('SPAM', 'OFFENSIVE', 'MISINFORMATION', 'HARASSMENT', 'OTHER')),
    CONSTRAINT ck_community_reports_status CHECK (status IN ('OPEN', 'DISMISSED', 'ACTION_TAKEN')),
    CONSTRAINT ck_community_reports_note CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT ck_community_reports_resolution CHECK (resolution_note IS NULL OR char_length(resolution_note) <= 500)
);
CREATE UNIQUE INDEX ux_community_reports_post_reporter ON app.community_reports(post_id, reporter_id);
CREATE INDEX ix_community_reports_status ON app.community_reports(status, created_at DESC, id DESC);
CREATE INDEX ix_community_reports_reporter ON app.community_reports(reporter_id, created_at);

-- ---------------------------------------------------------------------------------------------
-- Thông báo cộng đồng: tách khỏi app.notifications (chuông header).
-- dedup_key chặn thông báo trùng: tim/bỏ tim/tim lại chỉ ra một thông báo.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE app.community_notifications (
    id VARCHAR(36) NOT NULL,
    recipient_id VARCHAR(36) NOT NULL,
    type VARCHAR(30) NOT NULL,
    post_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NULL,
    comment_id VARCHAR(36) NULL,
    dedup_key VARCHAR(200) NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_community_notifications PRIMARY KEY (id),
    CONSTRAINT fk_community_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES app.users(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_notifications_post FOREIGN KEY (post_id) REFERENCES app.community_posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_notifications_actor FOREIGN KEY (actor_id) REFERENCES app.users(id) ON DELETE SET NULL,
    CONSTRAINT ck_community_notifications_type CHECK (type IN ('POST_LIKED', 'POST_COMMENTED', 'POST_APPROVED', 'POST_REJECTED', 'POST_REMOVED'))
);
CREATE UNIQUE INDEX ux_community_notifications_dedup ON app.community_notifications(dedup_key);
CREATE INDEX ix_community_notifications_recipient ON app.community_notifications(recipient_id, created_at DESC, id DESC);
CREATE INDEX ix_community_notifications_unread ON app.community_notifications(recipient_id, read_at);
