package vn.nutrimom.family.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.family.domain.FamilyInvitationEntity;

public interface FamilyInvitationRepository extends JpaRepository<FamilyInvitationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from FamilyInvitationEntity invitation "
            + "where invitation.tokenHash = :tokenHash")
    Optional<FamilyInvitationEntity> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash);

    /**
     * Khoá cùng một dòng mà {@link #findByTokenHashForUpdate} khoá, chỉ khác đường tra.
     *
     * <p>Cần cho {@code revoke}: entity này không có {@code @Version}, nên khoá bi quan là cơ chế
     * duy nhất ngăn thu hồi và chấp nhận cùng thắng.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invitation from FamilyInvitationEntity invitation where invitation.id = :id")
    Optional<FamilyInvitationEntity> findByIdForUpdate(@Param("id") String id);

    /** Xem trước lời mời — chỉ đọc nên không khoá hàng như lúc accept. */
    Optional<FamilyInvitationEntity> findByTokenHash(String tokenHash);

    List<FamilyInvitationEntity> findByFamilyGroupIdOrderByCreatedAtDesc(String familyGroupId);
}
