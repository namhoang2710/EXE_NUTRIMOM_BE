package vn.nutrimom.payment;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    vn.nutrimom.consultation.video.LiveKitClient livekit;

    @Test
    void userCreatesPaymentAndChecksSubscription() throws Exception {
        String userId = createUserAccount("0987654321", "Mẹ Bầu Test");

        // 1. Kiểm tra ban đầu: gói mặc định là FREE
        mockMvc.perform(get("/api/v1/payments/my-subscription").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_tier").value("FREE"))
                .andExpect(jsonPath("$.data.active").value(true));

        // 2. Không cho phép tạo đơn thanh toán cho gói FREE
        mockMvc.perform(post("/api/v1/payments/subscription-checkout")
                        .with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_tier\": \"FREE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PLAN_TIER"));

        // 3. Tạo thanh toán cho gói PLAN_99K
        var result = mockMvc.perform(post("/api/v1/payments/subscription-checkout")
                        .with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_tier\": \"PLAN_99K\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.plan_tier").value("PLAN_99K"))
                .andExpect(jsonPath("$.data.amount").value(99000))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.checkout_url").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        var jsonNode = objectMapper.readTree(responseBody);
        long orderCode = jsonNode.get("data").get("order_code").asLong();

        // 4. Lấy chi tiết đơn hàng
        mockMvc.perform(get("/api/v1/payments/orders/{orderCode}", orderCode).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_code").value(orderCode))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // 5. Xác nhận thanh toán (Mock confirmation)
        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/confirm-mock", orderCode).with(userJwt(userId)))
                .andExpect(status().isOk());

        // 6. Kiểm tra lại Subscription: đã được nâng cấp lên PLAN_99K
        mockMvc.perform(get("/api/v1/payments/my-subscription").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_tier").value("PLAN_99K"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.days_remaining").value(30));
    }

    @Test
    void transactionHistoryIsPagedAndFilterable() throws Exception {
        String userId = createUserAccount("0987650001", "Mẹ Bầu Lịch Sử");

        long paid = createOrder(userId, "PLAN_99K");
        mockMvc.perform(post("/api/v1/payments/orders/{orderCode}/confirm-mock", paid).with(userJwt(userId)))
                .andExpect(status().isOk());
        long pending = createOrder(userId, "PLAN_399K");

        // Phân trang: 2 đơn, mỗi trang 1 — đơn mới nhất đứng trước (created_at DESC).
        mockMvc.perform(get("/api/v1/payments/orders")
                        .param("page", "1").param("pageSize", "1").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].order_code").value(pending))
                .andExpect(jsonPath("$.data.items[0].plan_tier").value("PLAN_399K"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.page_size").value(1))
                .andExpect(jsonPath("$.data.total_items").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(2));

        mockMvc.perform(get("/api/v1/payments/orders")
                        .param("page", "2").param("pageSize", "1").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].order_code").value(paid));

        // Lọc theo trạng thái và theo gói.
        mockMvc.perform(get("/api/v1/payments/orders").param("status", "PAID").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].order_code").value(paid))
                .andExpect(jsonPath("$.data.items[0].paid_at").isNotEmpty());

        mockMvc.perform(get("/api/v1/payments/orders").param("planTier", "PLAN_399K").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].order_code").value(pending));

        // Khoảng ngày bao trọn cả ngày toDate.
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        mockMvc.perform(get("/api/v1/payments/orders")
                        .param("fromDate", today).param("toDate", today).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(2));

        mockMvc.perform(get("/api/v1/payments/orders")
                        .param("fromDate", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString())
                        .with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(0));

        // pageSize vượt trần là lỗi validation, không phải im lặng cắt về 100.
        mockMvc.perform(get("/api/v1/payments/orders").param("pageSize", "101").with(userJwt(userId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /**
     * Đơn của người khác và đơn không tồn tại cho ra cùng một 404.
     *
     * <p>Trả 403 là tự xác nhận "đơn này có thật, chỉ không phải của bạn" — xem
     * {@code docs/security.md} mục 2.</p>
     */
    @Test
    void oneUserCannotReadAnotherUsersOrder() throws Exception {
        String owner = createUserAccount("0987650002", "Chủ Đơn");
        String stranger = createUserAccount("0987650003", "Người Lạ");
        long orderCode = createOrder(owner, "PLAN_99K");

        mockMvc.perform(get("/api/v1/payments/orders/{orderCode}", orderCode).with(userJwt(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ORDER_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/payments/orders/{orderCode}", 999999999L).with(userJwt(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_ORDER_NOT_FOUND"));

        // Lịch sử của người lạ không thấy đơn đó.
        mockMvc.perform(get("/api/v1/payments/orders").with(userJwt(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(0));
    }

    private long createOrder(String userId, String planTier) throws Exception {
        var result = mockMvc.perform(post("/api/v1/payments/subscription-checkout")
                        .with(userJwt(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan_tier\": \"" + planTier + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("order_code").asLong();
    }

    private String createUserAccount(String phone, String name) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(name);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", "ROLE_USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
