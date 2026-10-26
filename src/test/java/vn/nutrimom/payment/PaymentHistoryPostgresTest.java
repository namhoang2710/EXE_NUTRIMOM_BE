package vn.nutrimom.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.PageResponse;
import vn.nutrimom.payment.domain.PaymentOrderStatus;
import vn.nutrimom.payment.domain.PlanTier;
import vn.nutrimom.payment.dto.PaymentOrderResponse;
import vn.nutrimom.payment.service.PaymentService;

/**
 * Lịch sử giao dịch chạy trên PostgreSQL thật, rollback-only.
 *
 * <p><strong>Vì sao phải là PostgreSQL:</strong> bản cũ của truy vấn này dùng
 * {@code (:from is null or p.createdAt >= :from)}. Null {@code OffsetDateTime} xuống tới driver
 * thành {@code setNull(idx, Types.TIMESTAMP[_WITH_TIMEZONE])}, mà pgjdbc ánh xạ cả hai mã ấy sang
 * oid {@code UNSPECIFIED}; trong vế {@code $1 is null} thì Postgres không còn chỗ nào suy ra kiểu
 * và ném {@code 42P18}, tức HTTP 500 cho mọi request không gửi đủ cả hai ngày. H2 ở profile test
 * không ép suy luận kiểu tham số nên nó cho qua tuốt — bộ test H2 đã xanh suốt trong khi endpoint
 * chết trên production. Đừng chuyển các ca dưới đây sang H2.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfSystemProperty(named = "payment.compatibility.test", matches = "true")
class PaymentHistoryPostgresTest {

    private static final String QA_URL = "jdbc:postgresql://127.0.0.1:55439/admin_qa";

    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired PaymentService paymentService;
    @MockitoBean vn.nutrimom.assistant.service.GroqAssistantClient groq;
    @MockitoBean vn.nutrimom.consultation.video.LiveKitClient livekit;

    @Test
    void everyFilterCombinationAnswersWithoutATypeInferenceFailure() {
        String user = createUser();
        createOrder(user, "PLAN_99K", "PAID", "2026-01-10T03:00:00Z");
        createOrder(user, "PLAN_399K", "PENDING", "2026-02-15T03:00:00Z");
        createOrder(user, "PLAN_99K", "CANCELLED", "2026-03-20T03:00:00Z");
        createOrder(user, "PLAN_399K", "PAID", "2026-03-20T03:00:00Z");

        // Không bộ lọc nào: đây và ca status-only bên dưới là hai ca 42P18 của bản cũ.
        assertEquals(4, history(user, null, null, null, null).totalItems());

        assertEquals(2, history(user, PaymentOrderStatus.PAID, null, null, null).totalItems());
        assertEquals(2, history(user, null, PlanTier.PLAN_99K, null, null).totalItems());
        assertEquals(1, history(user, PaymentOrderStatus.PAID, PlanTier.PLAN_399K, null, null).totalItems());

        // Chỉ cận dưới, chỉ cận trên, rồi cả hai. toDate phải bao trọn ngày của nó.
        assertEquals(2, history(user, null, null, LocalDate.parse("2026-03-01"), null).totalItems());
        assertEquals(1, history(user, null, null, null, LocalDate.parse("2026-01-10")).totalItems());
        assertEquals(1, history(user, null, null,
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28")).totalItems());

        // Không có đơn nào khớp vẫn là 200 với trang rỗng, không phải lỗi.
        PageResponse<PaymentOrderResponse> empty = history(user, PaymentOrderStatus.EXPIRED, null, null, null);
        assertEquals(0, empty.totalItems());
        assertEquals(0, empty.totalPages());
        assertTrue(empty.items().isEmpty());
    }

    @Test
    void pagingIsStableAndNeverLeaksAnotherUsersOrders() {
        String user = createUser();
        // Cùng created_at để ép thứ tự phải dựa vào id DESC, nếu không trang 2 sẽ lặp dòng của trang 1.
        for (int i = 0; i < 4; i++) {
            createOrder(user, "PLAN_99K", "PAID", "2026-04-01T03:00:00Z");
        }
        String stranger = createUser();
        createOrder(stranger, "PLAN_399K", "PAID", "2026-04-01T03:00:00Z");

        List<String> first = history(user, null, null, null, null, 1, 2).items()
                .stream().map(PaymentOrderResponse::id).toList();
        List<String> second = history(user, null, null, null, null, 2, 2).items()
                .stream().map(PaymentOrderResponse::id).toList();

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(first.stream().noneMatch(second::contains), "trang 2 lặp lại dòng của trang 1");
        assertEquals(4, history(user, null, null, null, null).totalItems());
        assertEquals(1, history(stranger, null, null, null, null).totalItems());
    }

    private PageResponse<PaymentOrderResponse> history(String userId, PaymentOrderStatus status,
                                                       PlanTier planTier, LocalDate from, LocalDate to) {
        return history(userId, status, planTier, from, to, 1, 20);
    }

    private PageResponse<PaymentOrderResponse> history(String userId, PaymentOrderStatus status,
                                                       PlanTier planTier, LocalDate from, LocalDate to,
                                                       int page, int pageSize) {
        return paymentService.getUserOrders(userId, page, pageSize, status, planTier, from, to);
    }

    private String createUser() {
        guardDatabase();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into app.users(id,phone,display_name,status,created_at,updated_at,version,onboarding_status)
                values(?,?,?,'ACTIVE',current_timestamp,current_timestamp,0,'COMPLETED')""",
                id, "hist-" + id.substring(0, 8), "History QA");
        return id;
    }

    private void createOrder(String userId, String planTier, String status, String createdAt) {
        guardDatabase();
        OffsetDateTime created = OffsetDateTime.parse(createdAt);
        jdbc.update("""
                insert into app.payment_orders(id,order_code,user_id,plan_tier,amount,currency,status,
                    created_at,updated_at,version)
                values(?,?,?,?,?,'VND',?,?,?,0)""",
                UUID.randomUUID().toString(),
                Math.abs(UUID.randomUUID().getMostSignificantBits()),
                userId,
                planTier,
                "PLAN_99K".equals(planTier) ? 99000 : 399000,
                status,
                created,
                created);
    }

    /** Fixture chỉ được chạm DB QA rời; không bao giờ trỏ vào DB ứng dụng dùng chung. */
    private void guardDatabase() {
        assertEquals(QA_URL, env.getProperty("spring.datasource.url"));
    }
}
