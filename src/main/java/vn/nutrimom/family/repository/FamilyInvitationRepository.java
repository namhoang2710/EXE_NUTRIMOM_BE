package vn.nutrimom.family.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.domain.InvitationDeliveryStatus;

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

    /**
     * Ghi kết quả gửi email, sau khi transaction tạo lời mời đã commit.
     *
     * <p>Phải là UPDATE nhắm đúng hai cột, <strong>không</strong> được là {@code save(entity)}.
     * {@code spring.jpa.open-in-view: false} nên entity dựng ở transaction trước đã detach, và
     * {@code save} một entity detach là {@code merge} — tức UPDATE cả hàng từ ảnh chụp cũ. Khoảng
     * giữa hai transaction dài đúng bằng độ trễ SMTP, tính bằng giây; trong khoảng đó chủ nhóm có
     * thể {@code revoke} hoặc người được mời có thể {@code accept}, và merge sẽ ghi đè
     * {@code status=PENDING, revoked_at=null, accepted_at=null}, âm thầm huỷ thao tác của họ.
     * {@code FamilyInvitationEntity} không có {@code @Version} nên không có lưới đỡ nào bắt được.</p>
     *
     * <p>Người gọi tự mở transaction, như mọi method khác ở đây.</p>
     *
     * @return số dòng chạm được; 0 nghĩa là lời mời đã biến mất, không phải lỗi.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FamilyInvitationEntity i set i.deliveryStatus = :status, i.sentAt = :sentAt "
            + "where i.id = :id")
    int recordDelivery(@Param("id") String id,
                       @Param("status") InvitationDeliveryStatus status,
                       @Param("sentAt") OffsetDateTime sentAt);

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
