package vn.nutrimom.review.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.review.domain.WebReviewEntity;
import vn.nutrimom.review.domain.WebReviewStatus;

public interface WebReviewRepository extends JpaRepository<WebReviewEntity, String> {

    Optional<WebReviewEntity> findByAuthorIdAndDeletedAtIsNull(String authorId);

    /**
     * Trang công khai: chỉ PUBLISHED của tài khoản còn ACTIVE (tài khoản bị khoá thì đánh giá tự rút khỏi
     * trang mà không phải sửa dữ liệu). Keyset (created_at DESC, id DESC), lấy {@code limit + 1} dòng.
     */
    @Query("""
            select r from WebReviewEntity r
            where r.status = vn.nutrimom.review.domain.WebReviewStatus.PUBLISHED
              and exists (select 1 from UserEntity u where u.id = r.authorId
                          and u.status = vn.nutrimom.auth.domain.UserStatus.ACTIVE)
              and (:rating is null or r.rating = :rating)
              and (r.createdAt < :at or (r.createdAt = :at and r.id < :id))
            order by r.createdAt desc, r.id desc
            """)
    List<WebReviewEntity> findPublicPage(@Param("rating") Short rating, @Param("at") OffsetDateTime at,
            @Param("id") String id, Pageable window);

    /** Số đánh giá theo từng mức sao, cùng điều kiện với trang công khai. */
    @Query("""
            select r.rating, count(r) from WebReviewEntity r
            where r.status = vn.nutrimom.review.domain.WebReviewStatus.PUBLISHED
              and exists (select 1 from UserEntity u where u.id = r.authorId
                          and u.status = vn.nutrimom.auth.domain.UserStatus.ACTIVE)
            group by r.rating
            """)
    List<Object[]> countPublicByRating();

    /**
     * Danh sách admin: mọi trạng thái, lọc tuỳ chọn. {@code replied}: null = tất cả, true = đã trả lời,
     * false = chưa. {@code q} (đã viết thường, bọc %) tìm trong nội dung và tên người viết.
     */
    @Query("""
            select r from WebReviewEntity r
            where (:status is null or r.status = :status)
              and (:rating is null or r.rating = :rating)
              and (:replied is null or (:replied = true and r.replyContent is not null)
                                    or (:replied = false and r.replyContent is null))
              and (:q is null or lower(r.content) like :q escape '!'
                   or exists (select 1 from UserEntity u where u.id = r.authorId and lower(u.displayName) like :q escape '!'))
              and (r.createdAt < :at or (r.createdAt = :at and r.id < :id))
            order by r.createdAt desc, r.id desc
            """)
    List<WebReviewEntity> findAdminPage(@Param("status") WebReviewStatus status, @Param("rating") Short rating,
            @Param("replied") Boolean replied, @Param("q") String q, @Param("at") OffsetDateTime at,
            @Param("id") String id, Pageable window);

    /** Badge cho admin: đánh giá đang hiện công khai mà chưa có trả lời. */
    long countByStatusAndReplyContentIsNull(WebReviewStatus status);
}
