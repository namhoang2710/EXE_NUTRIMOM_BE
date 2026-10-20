package vn.nutrimom.saved;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.domain.CommunityClock;
import vn.nutrimom.community.service.CommunityCursor;
import vn.nutrimom.community.service.CommunityText;

/**
 * Danh sách "Đã lưu" gộp bài Knowledge và bài Cộng đồng.
 *
 * <p>Hai loại tài nguyên giữ hai bảng riêng ({@code knowledge_article_bookmarks} có FK cứng tới bài
 * Knowledge), API bookmark Knowledge cũ giữ nguyên. Ở đây chỉ ĐỌC hai bảng bằng một câu UNION ALL,
 * sắp theo thời điểm lưu. Mỗi item có {@code type} + {@code id}/{@code slug} để FE điều hướng đúng
 * trang. Chỉ trả bài còn hiển thị: Knowledge đã xuất bản, Cộng đồng đã duyệt và chưa bị xoá.</p>
 */
@Service
public class SavedItemService {
    public enum SavedType { ALL, KNOWLEDGE, COMMUNITY }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SavedItem(String type, String id, String slug, String title, String excerpt,
                            String thumbnailUrl, OffsetDateTime savedAt) { }

    private static final String SQL = """
            select * from (
                select 'KNOWLEDGE' as item_type, a.id as item_id, a.slug as slug, a.title as title,
                       a.excerpt as excerpt, a.cover_image_url as thumbnail_url, b.created_at as saved_at
                from app.knowledge_article_bookmarks b
                join app.knowledge_articles a on a.id = b.article_id
                where b.user_id = :userId and :includeKnowledge = true
                  and a.status = 'published' and a.published_at <= :now
                union all
                select 'COMMUNITY', p.id, null, null, p.content,
                       (select i.image_url from app.community_post_images i
                        where i.post_id = p.id and i.status = 'ATTACHED' order by i.sort_order limit 1),
                       s.created_at
                from app.community_post_saves s
                join app.community_posts p on p.id = s.post_id
                where s.user_id = :userId and :includeCommunity = true
                  and p.status = 'APPROVED' and p.deleted_at is null
            ) saved
            where saved.saved_at < :at or (saved.saved_at = :at and saved.item_id < :id)
            order by saved.saved_at desc, saved.item_id desc
            limit :window
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SavedItemService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CursorPage<SavedItem> list(String userId, SavedType type, String cursor, int limit) {
        if (limit < 1 || limit > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit phải từ 1 đến 50.");
        }
        CommunityCursor from = CommunityCursor.descending(cursor);
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("includeKnowledge", type != SavedType.COMMUNITY)
                .addValue("includeCommunity", type != SavedType.KNOWLEDGE)
                .addValue("now", CommunityClock.now())
                .addValue("at", from.at())
                .addValue("id", from.id())
                .addValue("window", limit + 1);
        List<SavedItem> rows = jdbc.query(SQL, params, (rs, i) -> new SavedItem(
                rs.getString("item_type"), rs.getString("item_id"), rs.getString("slug"), rs.getString("title"),
                CommunityText.excerpt(rs.getString("excerpt"), 200), rs.getString("thumbnail_url"),
                rs.getObject("saved_at", OffsetDateTime.class)));
        var page = CommunityCursor.window(rows, limit, SavedItem::savedAt, SavedItem::id);
        return new CursorPage<>(page.items(), page.nextCursor(), page.hasMore());
    }
}
