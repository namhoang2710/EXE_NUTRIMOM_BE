package vn.nutrimom.family.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import vn.nutrimom.family.domain.FamilyRelationship;
import vn.nutrimom.family.domain.FamilyScope;

/**
 * Nhãn tiếng Việt của quyền và quan hệ gia đình.
 *
 * <p>Dùng chung cho email mời, thông báo in-app và response xem trước lời mời. Để mỗi nơi tự dịch
 * sẽ dẫn tới chuyện người nhà đọc email thấy một cách gọi, mở web lại thấy cách gọi khác cho đúng
 * một quyền.</p>
 *
 * <p>Nhãn mô tả <em>điều người nhà sẽ làm được</em>, không phải tên hằng số: "SHARED_CALENDAR"
 * không nói gì với người đang quyết định có nhận lời mời hay không.</p>
 */
public final class FamilyScopeLabels {

    private static final Map<FamilyScope, String> SCOPES = Map.of(
            FamilyScope.PREGNANCY_SUMMARY, "Xem tổng quan thai kỳ (tuần thai, ngày dự sinh)",
            FamilyScope.FAMILY_TASKS, "Xem và nhận việc nhà được giao",
            FamilyScope.SHARED_CALENDAR, "Xem lịch khám và nhắc nhở",
            FamilyScope.ALERTS, "Nhận cảnh báo cần chú ý",
            FamilyScope.ACTIVITY_FEED, "Xem dòng hoạt động của nhóm",
            FamilyScope.MEDICAL_RECORDS, "Xem hồ sơ y tế");

    private static final Map<FamilyRelationship, String> RELATIONSHIPS = Map.of(
            FamilyRelationship.PARTNER, "Chồng/bạn đời",
            FamilyRelationship.SPOUSE, "Vợ/chồng",
            FamilyRelationship.PARENT, "Bố/mẹ",
            FamilyRelationship.SIBLING, "Anh/chị/em",
            FamilyRelationship.RELATIVE, "Người thân",
            FamilyRelationship.FRIEND, "Bạn bè",
            FamilyRelationship.OTHER, "Người thân khác");

    private FamilyScopeLabels() {
    }

    public static String of(FamilyScope scope) {
        return SCOPES.getOrDefault(scope, scope.name());
    }

    public static String of(FamilyRelationship relationship) {
        return RELATIONSHIPS.getOrDefault(relationship, relationship.name());
    }

    /** Sắp theo thứ tự khai báo enum để hai lần gọi cho ra cùng một thứ tự. */
    public static List<String> of(Set<FamilyScope> scopes) {
        return scopes.stream().sorted().map(FamilyScopeLabels::of).toList();
    }
}
