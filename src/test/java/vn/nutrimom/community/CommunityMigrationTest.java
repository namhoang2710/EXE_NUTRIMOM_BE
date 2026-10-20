package vn.nutrimom.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Chạy file V65 thật (H2 chế độ PostgreSQL) và kiểm các ràng buộc mà database chịu trách nhiệm:
 * tim/lưu/báo cáo/thông báo không thể trùng, ảnh và bài không thể ở trạng thái vô nghĩa.
 * Integration test dựng schema bằng ddl-auto nên không chạy file .sql; vẫn phải chạy V65 một lượt trên
 * PostgreSQL thật trước khi merge.
 */
class CommunityMigrationTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V65__community.sql");
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";
    private static final String POST = "33333333-3333-3333-3333-333333333333";

    private Connection db;

    @BeforeEach
    void applyMigration() throws Exception {
        db = DriverManager.getConnection("jdbc:h2:mem:community_v65_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        execute("CREATE SCHEMA IF NOT EXISTS app");
        execute("CREATE TABLE app.users (id VARCHAR(36) NOT NULL PRIMARY KEY)");
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) execute(statement);
        }
        execute("INSERT INTO app.users (id) VALUES ('" + USER + "'), ('" + OTHER + "')");
        execute("INSERT INTO app.community_posts (id, author_id, content, topic, status, published_at, created_at, updated_at) "
                + "VALUES ('" + POST + "', '" + USER + "', 'x', 'OTHER', 'APPROVED', NOW(), NOW(), NOW())");
    }

    @AfterEach
    void close() throws SQLException {
        if (db != null) db.close();
    }

    @Test
    void likeSaveAndReportAreUniquePerUser() throws Exception {
        execute("INSERT INTO app.community_post_likes VALUES ('" + POST + "', '" + OTHER + "', NOW())");
        assertThatThrownBy(() -> execute("INSERT INTO app.community_post_likes VALUES ('" + POST + "', '" + OTHER + "', NOW())"))
                .isInstanceOf(SQLException.class);
        execute("INSERT INTO app.community_post_saves VALUES ('" + POST + "', '" + OTHER + "', NOW())");
        assertThatThrownBy(() -> execute("INSERT INTO app.community_post_saves VALUES ('" + POST + "', '" + OTHER + "', NOW())"))
                .isInstanceOf(SQLException.class);
        execute(report("r1"));
        assertThatThrownBy(() -> execute(report("r2"))).isInstanceOf(SQLException.class);
    }

    @Test
    void notificationDedupKeyIsUnique() throws Exception {
        execute(notification("n1", "LIKE:p:u"));
        assertThatThrownBy(() -> execute(notification("n2", "LIKE:p:u"))).isInstanceOf(SQLException.class);
    }

    @Test
    void approvedPostNeedsPublishedAtAndValidEnums() {
        // APPROVED thiếu published_at, chủ đề không tồn tại, nội dung rỗng.
        assertThatThrownBy(() -> execute(post("p2", "x", "OTHER", "APPROVED"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(post("p3", "x", "VIDEO", "PENDING"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(post("p4", "", "OTHER", "PENDING"))).isInstanceOf(SQLException.class);
    }

    @Test
    void imageStatusShapeIsEnforced() throws Exception {
        execute(image("i1", "NULL", "'PENDING'", "NOW()"));
        // ATTACHED phải có bài và không có hạn xoá; PENDING phải có hạn xoá.
        assertThatThrownBy(() -> execute(image("i2", "NULL", "'ATTACHED'", "NULL"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(image("i3", "NULL", "'PENDING'", "NULL"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(image("i4", "NULL", "'PENDING'", "NOW()").replace("community/i4", "community/i1")))
                .isInstanceOf(SQLException.class);
        execute(image("i5", "'" + POST + "'", "'ATTACHED'", "NULL"));
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM app.community_post_images")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(2);
        }
    }

    private String post(String id, String content, String topic, String status) {
        return "INSERT INTO app.community_posts (id, author_id, content, topic, status, created_at, updated_at) "
                + "VALUES ('" + id + "', '" + USER + "', '" + content + "', '" + topic + "', '" + status + "', NOW(), NOW())";
    }

    private String report(String id) {
        return "INSERT INTO app.community_reports (id, post_id, reporter_id, reason, status, created_at, updated_at) "
                + "VALUES ('" + id + "', '" + POST + "', '" + OTHER + "', 'SPAM', 'OPEN', NOW(), NOW())";
    }

    private String notification(String id, String key) {
        return "INSERT INTO app.community_notifications (id, recipient_id, type, post_id, dedup_key, created_at) "
                + "VALUES ('" + id + "', '" + USER + "', 'POST_LIKED', '" + POST + "', '" + key + "', NOW())";
    }

    private String image(String id, String postId, String status, String deleteAfter) {
        return "INSERT INTO app.community_post_images (id, post_id, owner_id, image_key, image_url, width, height, "
                + "size_bytes, status, delete_after, created_at, updated_at) VALUES ('" + id + "', " + postId + ", '"
                + USER + "', 'community/" + id + "', 'https://x/" + id + "', 1, 1, 1, " + status + ", " + deleteAfter
                + ", NOW(), NOW())";
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            statement.execute(sql);
        }
    }
}
