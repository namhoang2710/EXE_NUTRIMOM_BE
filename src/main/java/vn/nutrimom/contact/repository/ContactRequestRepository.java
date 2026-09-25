package vn.nutrimom.contact.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.contact.domain.ContactRequestEntity;
import vn.nutrimom.contact.domain.ContactRequestStatus;
import vn.nutrimom.contact.domain.ContactTopic;

public interface ContactRequestRepository extends JpaRepository<ContactRequestEntity, String> {

    Page<ContactRequestEntity> findByUserIdOrderByCreatedAtDescIdDesc(String userId, Pageable pageable);

    Optional<ContactRequestEntity> findByIdAndUserId(String id, String userId);

    long countByUserIdAndStatus(String userId, ContactRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ContactRequestEntity request where request.id = :id")
    Optional<ContactRequestEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Hộp thư admin, phân trang ở DB. Tham số null = không lọc.
     * {@code pattern} là chuỗi LIKE đã chuẩn hoá chữ thường (vd {@code %lan%}), khớp tên hiển thị hoặc SĐT.
     */
    @Query(value = """
            select request from ContactRequestEntity request
            join UserEntity account on account.id = request.userId
            where (:status is null or request.status = :status)
              and (:topic is null or request.topic = :topic)
              and (:pattern is null or lower(account.displayName) like :pattern escape '\\' or account.phone like :pattern escape '\\')
            order by request.createdAt desc, request.id desc
            """,
            countQuery = """
            select count(request) from ContactRequestEntity request
            join UserEntity account on account.id = request.userId
            where (:status is null or request.status = :status)
              and (:topic is null or request.topic = :topic)
              and (:pattern is null or lower(account.displayName) like :pattern escape '\\' or account.phone like :pattern escape '\\')
            """)
    Page<ContactRequestEntity> searchForAdmin(@Param("status") ContactRequestStatus status,
                                              @Param("topic") ContactTopic topic,
                                              @Param("pattern") String pattern,
                                              Pageable pageable);
}
