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
    Optional<UserEntity> findByPhone(String phone);
    boolean existsByPhone(String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") String id);

    @Query(value = """
            select user.id
            from UserEntity user
            where (:search is null
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
            where (:search is null
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

    long countByStatus(UserStatus status);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            OffsetDateTime startInclusive, OffsetDateTime endExclusive);
}
