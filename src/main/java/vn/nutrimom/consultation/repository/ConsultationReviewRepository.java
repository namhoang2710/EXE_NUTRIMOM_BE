package vn.nutrimom.consultation.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.consultation.domain.ConsultationReviewEntity;

public interface ConsultationReviewRepository
        extends JpaRepository<ConsultationReviewEntity, String> {

    boolean existsByRequestId(String requestId);

    Optional<ConsultationReviewEntity> findByRequestId(String requestId);

    /** Lọc đánh giá của chuyên gia theo số sao và/hoặc khoảng thời gian (mọi tham số tùy chọn). */
    @Query("""
            select review from ConsultationReviewEntity review
            where review.expertUserId = :expertUserId
              and (:rating is null or review.rating = :rating)
              and (:from is null or review.createdAt >= :from)
              and (:to is null or review.createdAt < :to)
            order by review.createdAt desc
            """)
    List<ConsultationReviewEntity> search(
            @Param("expertUserId") String expertUserId,
            @Param("rating") Short rating,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
