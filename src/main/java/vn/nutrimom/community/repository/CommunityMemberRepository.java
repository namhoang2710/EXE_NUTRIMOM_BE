package vn.nutrimom.community.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;

/**
 * Đọc thông tin hiển thị của người dùng cho cộng đồng.
 *
 * <p>Chỉ chọn cột (projection), không nạp {@link UserEntity}: entity nạp roles kiểu EAGER, nạp nó cho
 * từng tác giả trong feed sẽ thành N+1. Cũng nhờ vậy email/số điện thoại không bao giờ đi qua đây.</p>
 */
public interface CommunityMemberRepository extends Repository<UserEntity, String> {

    @Query("""
            select new vn.nutrimom.community.repository.MemberView(
                u.id, u.displayName, u.avatarKey,
                case when exists (select 1 from UserEntity a join a.roles r
                                  where a.id = u.id and r = vn.nutrimom.auth.domain.UserRole.ADMIN)
                     then true else false end)
            from UserEntity u where u.id in :ids
            """)
    List<MemberView> findViews(@Param("ids") Collection<String> ids);

    @Query("select u.status from UserEntity u where u.id = :id")
    Optional<UserStatus> findStatus(@Param("id") String id);

    /** Admin đang hoạt động (cùng điều kiện với ActiveAdminFilter). */
    @Query("""
            select count(u) > 0 from UserEntity u
            where u.id = :id and u.status = vn.nutrimom.auth.domain.UserStatus.ACTIVE
              and vn.nutrimom.auth.domain.UserRole.ADMIN member of u.roles
            """)
    boolean isActiveAdmin(@Param("id") String id);
}
