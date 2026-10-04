package vn.nutrimom.auth.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;

public interface UserRepository extends JpaRepository<UserEntity, String> {
    interface UserDisplayNameView {
        String getId();
        String getDisplayName();
    }

    Optional<UserEntity> findByPhone(String phone);
    boolean existsByPhone(String phone);
    Optional<UserEntity> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<UserEntity> findByEmailActivationTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") String id);

    @Query(value = """
            select user.id
            from UserEntity user
            where (:search = ''
                    or lower(user.displayName) like lower(concat('%', :search, '%'))
                    or lower(user.phone) like lower(concat('%', :search, '%'))
                    or lower(user.email) like lower(concat('%', :search, '%')))
              and (:status is null or user.status = :status)
              and (:role is null or :role member of user.roles)
              and (:onboardingStatus is null or user.onboardingStatus = :onboardingStatus)
            """,
            countQuery = """
            select count(user.id)
            from UserEntity user
            where (:search = ''
                    or lower(user.displayName) like lower(concat('%', :search, '%'))
                    or lower(user.phone) like lower(concat('%', :search, '%'))
                    or lower(user.email) like lower(concat('%', :search, '%')))
              and (:status is null or user.status = :status)
              and (:role is null or :role member of user.roles)
              and (:onboardingStatus is null or user.onboardingStatus = :onboardingStatus)
            """)
    Page<String> findAdminUserIds(
            @Param("search") String search,
            @Param("status") UserStatus status,
            @Param("role") UserRole role,
            @Param("onboardingStatus") OnboardingStatus onboardingStatus,
            Pageable pageable);

    @Query("""
            select distinct user
            from UserEntity user
            left join fetch user.roles
            where user.id in :ids
            """)
    List<UserEntity> findAllWithRolesByIdIn(@Param("ids") Collection<String> ids);

    @Query("""
            select distinct user
            from UserEntity user
            left join fetch user.roles
            where user.id = :id
            """)
    Optional<UserEntity> findByIdWithRoles(@Param("id") String id);

    @Query("""
            select user.id as id, user.displayName as displayName
            from UserEntity user
            where user.id in :ids
            """)
    List<UserDisplayNameView> findDisplayNamesByIdIn(@Param("ids") Collection<String> ids);

    /**
     * Id của mọi tài khoản đang hoạt động mang một vai trò. Dùng khi phải phát thông báo cho cả một
     * vai trò thay vì một người cụ thể (vd hộp thư hỗ trợ báo cho toàn bộ ADMIN).
     */
    @Query("""
            select user.id
            from UserEntity user
            where :role member of user.roles and user.status = :status
            """)
    List<String> findIdsByRoleAndStatus(@Param("role") UserRole role,
                                        @Param("status") UserStatus status);

    long countByStatus(UserStatus status);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            OffsetDateTime startInclusive, OffsetDateTime endExclusive);
}
