package vn.nutrimom.payment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.nutrimom.common.api.ApiResponse;
import vn.nutrimom.common.api.ApiResponses;
import vn.nutrimom.payment.dto.CreatePaymentRequest;
import vn.nutrimom.payment.dto.PaymentOrderResponse;
import vn.nutrimom.payment.dto.PaymentResponse;
import vn.nutrimom.payment.dto.SubscriptionResponse;
import vn.nutrimom.payment.service.PaymentService;
import vn.payos.model.webhooks.Webhook;

@Validated
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments & Subscriptions", description = "Tích hợp cổng thanh toán VietQR PayOS và quản lý gói hội viên NutriMom")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/subscription-checkout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Tạo đơn hàng và link thanh toán VietQR qua PayOS")
    public ResponseEntity<ApiResponse<PaymentResponse>> createSubscriptionPayment(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse response = paymentService.createSubscriptionPayment(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponses.success(response));
    }

    @GetMapping("/my-subscription")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Lấy thông tin gói dịch vụ hiện tại của người dùng")
    public ApiResponse<SubscriptionResponse> getMySubscription(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(paymentService.getUserSubscription(jwt.getSubject()));
    }

    @GetMapping("/orders/{orderCode}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Xem chi tiết trạng thái đơn hàng thanh toán")
    public ApiResponse<PaymentOrderResponse> getOrderDetails(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long orderCode) {
        return ApiResponses.success(paymentService.getOrderDetails(jwt.getSubject(), orderCode));
    }

    @GetMapping("/orders")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Lịch sử các đơn thanh toán của người dùng")
    public ApiResponse<List<PaymentOrderResponse>> getUserOrders(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponses.success(paymentService.getUserOrders(jwt.getSubject()));
    }

    @PostMapping("/webhook")
    @Operation(summary = "Webhook tiếp nhận kết quả giao dịch tự động từ PayOS")
    public ResponseEntity<Map<String, Object>> handlePayOSWebhook(@RequestBody Webhook webhook) {
        paymentService.handlePayOSWebhook(webhook);
        return ResponseEntity.ok(Map.of("code", "00", "message", "success"));
    }

    @PostMapping("/orders/{orderCode}/confirm-mock")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Kích hoạt nhanh đơn hàng ở chế độ Test/Demo (dành cho thử nghiệm local)")
    public ApiResponse<String> confirmMockPayment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long orderCode) {
        paymentService.confirmMockPayment(orderCode);
        return ApiResponses.success("Thanh toán demo thành công, gói dịch vụ đã được kích hoạt.");
    }
}
