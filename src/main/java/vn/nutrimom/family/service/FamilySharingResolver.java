package vn.nutrimom.family.service;

import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;

/**
 * Phân giải "người đang gọi là thành viên của nhóm nào, và được cấp những quyền gì".
 *
 * <p>Logic này trước đây nằm private trong {@code PartnerDashboardService}. Khi lịch chia sẻ cũng
 * cần nó, hai bản sao sẽ thành hai nơi có thể quên kiểm scope — nên gom về một chỗ.</p>
 *
 * <p>Scope được đọc lại từ entity ở mỗi lần gọi, cố ý không cache: chủ thai kỳ gỡ một scope thì
 * lần gọi kế tiếp phải mất quyền ngay, không chờ hết phiên.</p>
 */
@Service
public class FamilySharingResolver {

    private final FamilyMemberRepository members;
    private final FamilyGroupRepository groups;

    public FamilySharingResolver(FamilyMemberRepository members, FamilyGroupRepository groups) {
        this.members = members;
        this.groups = groups;
    }

    /** Membership ACTIVE mới nhất có nhóm còn ACTIVE. Không có thì 403. */
    @Transactional(readOnly = true)
    public SharedContext requireMembership(String viewerUserId) {
        return members.findByUserIdAndStatusOrderByCreatedAtDesc(
                        viewerUserId, FamilyMemberStatus.ACTIVE)
                .stream()
                .map(member -> groups.findByIdAndStatus(
                                member.getFamilyGroupId(), FamilyGroupStatus.ACTIVE)
                        .map(group -> new SharedContext(member, group))
                        .orElse(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SHARING_SCOPE_REQUIRED,
                        "An active family membership is required."));
    }

    /**
     * Như {@link #requireMembership} nhưng đòi thêm một scope cụ thể.
     *
     * <p>Thiếu scope trả 403 chứ không 404: người gọi <em>là</em> thành viên hợp lệ, chỉ không đủ
     * quyền. Giấu đi thành 404 không che được gì mà làm client không phân biệt nổi "chưa được cấp
     * quyền" với "hỏng".</p>
     */
    @Transactional(readOnly = true)
    public SharedContext requireScope(String viewerUserId, FamilyScope scope) {
        SharedContext context = requireMembership(viewerUserId);
        if (!context.has(scope)) {
            throw new BusinessException(ErrorCode.SHARING_SCOPE_REQUIRED,
                    "The family membership does not grant " + scope.name() + ".");
        }
        return context;
    }

    /** Nhóm đang xem, thành viên tương ứng, và chủ thai kỳ mà dữ liệu thuộc về. */
    public record SharedContext(FamilyMemberEntity member, FamilyGroupEntity group) {

        /** Chủ thai kỳ — id dùng để truy vấn dữ liệu, KHÔNG phải id người đang xem. */
        public String ownerUserId() {
            return group.getOwnerUserId();
        }

        public Set<FamilyScope> scopes() {
            return member.getScopes();
        }

        public boolean has(FamilyScope scope) {
            return member.getScopes().contains(scope);
        }
    }
}
