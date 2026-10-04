package vn.nutrimom.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
