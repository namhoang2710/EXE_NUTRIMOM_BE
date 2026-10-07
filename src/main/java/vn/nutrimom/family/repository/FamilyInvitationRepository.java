package vn.nutrimom.family.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
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

    /**
     * Lời mời đang chờ một người cụ thể xử lý, tra theo chính địa chỉ đã được mời.
     *
     * <p>Chỉ {@code PENDING} và chưa hết hạn: danh sách này nuôi một cái badge, nên mọi dòng trong
     * đó phải bấm được. Lời mời đã chấp nhận thì xem ở danh sách thành viên; đã thu hồi thì người
     * được mời không làm gì được; đã hết hạn thì màn hình chi tiết vẫn giải thích được qua
     * {@code /{id}/preview}.</p>
     *
     * <p>Hai tham số kiểm null tường minh thay vì dựa vào {@code NULL = NULL}: tài khoản đăng ký
     * bằng OTP có số điện thoại mà không có email, nên một trong hai luôn null.</p>
     *
     * @param email đã {@code trim().toLowerCase()} ở tầng service — cột cũng lưu dạng đó, nên so
     *              sánh bằng dùng được index thường.
     * @param phone truyền THÔ, đúng như {@code accept} so khớp. Chuẩn hoá thêm một lần ở đây sẽ
     *              tạo ra hai định nghĩa "khớp" khác nhau cho cùng một lời mời.
     */
    @Query("""
            select invitation from FamilyInvitationEntity invitation
            where invitation.status = vn.nutrimom.family.domain.FamilyInvitationStatus.PENDING
              and invitation.expiresAt > :now
              and ((:email is not null and invitation.invitedEmail = :email)
                or (:phone is not null and invitation.invitedPhone = :phone))
            order by invitation.createdAt desc
            """)
    List<FamilyInvitationEntity> findReceived(@Param("email") String email,
                                              @Param("phone") String phone,
                                              @Param("now") OffsetDateTime now);
}
