package vn.nutrimom.entitlement.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.entitlement.PlanCatalog;
import vn.nutrimom.entitlement.dto.EntitlementDtos.MySubscriptionResponse;
import vn.nutrimom.entitlement.dto.EntitlementDtos.PlanResponse;
import vn.nutrimom.entitlement.service.EntitlementService;
import vn.nutrimom.entitlement.service.EntitlementService.PlanSnapshot;

/**
 * Catalog gói và quyền lợi của người gọi (spec mục 17).
 *
 * <p>Cố ý <strong>không</strong> gắn vào {@code /api/v1/payments/*}: module entitlement đã phụ thuộc
 * payment (lấy {@code PlanTier} + repository), nhét ngược entitlements vào DTO của payment sẽ thành
 * phụ thuộc vòng giữa hai package. Để {@code /payments/*} nguyên vẹn cũng giữ cho luồng thanh toán
 * FE đang chạy không bị động vào.</p>
 */
@RestController
@Tag(name = "Subscription plans & entitlements",
        description = "Bảng gói do server sở hữu và quyền lợi đã phân giải của người dùng")
public class SubscriptionPlanController {

    private final PlanCatalog catalog;
    private final EntitlementService entitlements;

    public SubscriptionPlanController(PlanCatalog catalog, EntitlementService entitlements) {
        this.catalog = catalog;
        this.entitlements = entitlements;
    }

    /**
     * Công khai: trang giá phải xem được khi chưa đăng nhập.
     *
     * <p>Đây là thứ thay cho bảng quyền lợi hardcode trong {@code pricing-catalog.ts} của FE.</p>
     */
    @GetMapping("/api/v1/subscription-plans")
    @Operation(summary = "Bảng gói kèm giá, thời hạn, quyền lợi và hạn mức scan do server sở hữu")
    public ApiResponse<List<PlanResponse>> plans() {
        return ApiResponses.success(catalog.all().stream().map(EntitlementMapper::plan).toList());
    }

    @GetMapping("/api/v1/subscriptions/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Gói hiện tại của người gọi và quyền lợi đã phân giải")
    public ApiResponse<MySubscriptionResponse> mySubscription(@AuthenticationPrincipal Jwt jwt) {
        PlanSnapshot snapshot = entitlements.snapshotOf(jwt.getSubject());
        return ApiResponses.success(new MySubscriptionResponse(
                snapshot.plan().code(),
                snapshot.plan().name(),
                snapshot.paid(),
                snapshot.startDate(),
                snapshot.endDate(),
                snapshot.daysRemaining(),
                EntitlementMapper.features(snapshot.plan().entitlements()),
                EntitlementMapper.scanQuota(snapshot.plan().entitlements().scanQuota())));
    }
}
