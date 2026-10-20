package vn.nutrimom.community.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.community.dto.CommunityDtos.Author;
import vn.nutrimom.community.repository.CommunityMemberRepository;
import vn.nutrimom.community.repository.MemberView;

/** Kiểm tra trạng thái tài khoản và nạp thông tin hiển thị của thành viên theo lô. */
@Component
public class CommunityMembers {
    private final CommunityMemberRepository members;

    public CommunityMembers(CommunityMemberRepository members) {
        this.members = members;
    }

    /** Chỉ tài khoản ACTIVE mới được đăng bài, bình luận, tim, lưu, báo cáo. */
    public void requireActive(String userId) {
        UserStatus status = members.findStatus(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (status != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa thể tham gia cộng đồng.");
        }
    }

    /** Một câu truy vấn cho cả trang; id không còn tồn tại thì không có trong map. */
    public Map<String, Author> authors(Collection<String> ids) {
        Map<String, Author> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (MemberView view : members.findViews(ids)) {
            result.put(view.id(), new Author(view.id(), view.displayName(), view.avatarKey()));
        }
        return result;
    }

    public static Author fallback(String id) {
        return new Author(id, "Thành viên", null);
    }
}
