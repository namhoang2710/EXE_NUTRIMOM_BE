package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.*;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.*;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;
import vn.nutrimom.admin.model.AdminQuery;
import vn.nutrimom.admin.repository.AdminOperationsRepository;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.payment.domain.*;
import vn.nutrimom.payment.repository.PaymentOrderRepository;

/**
 * Chốt việc gom theo ngày của admin luôn tính theo giờ Việt Nam, bất kể máy chạy test ở múi giờ nào.
 *
 * <p>Postgres tính {@code cast(x at time zone 'Asia/Ho_Chi_Minh' as date)} độc lập với múi giờ phiên:
 * {@code at time zone} trả về TIMESTAMP không múi giờ rồi mới cắt ngày. H2 thì giữ nguyên TIMESTAMP
 * WITH TIME ZONE và cắt ngày theo múi giờ PHIÊN, vốn thừa kế múi giờ JVM lúc mở database — nên nếu
 * không ghim múi giờ phiên, bộ test chỉ xanh trên máy chạy giờ Việt Nam. Ghim đó nằm ở
 * {@code spring.datasource.hikari.connection-init-sql} trong application-test.yml.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminOperationsTimeZoneTest {
    /** 00:00 ngày 01/10 giờ Việt Nam, nhưng đọc theo UTC thì vẫn là 30/09. */
    private static final OffsetDateTime ICT_MIDNIGHT = OffsetDateTime.parse("2026-09-30T17:00:00Z");
    private static final OffsetDateTime ONE_SECOND_EARLIER = ICT_MIDNIGHT.minusSeconds(1);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T05:00:00Z");
    private static final Period PERIOD =
            new Period(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1),
                       LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29));

    @Autowired AdminOperationsRepository repository;
    @Autowired UserRepository users;
    @Autowired PaymentOrderRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    @Transactional
    void collectionsAndRegistrationsBucketByIctCalendarDayNotUtc() {
        String early = member("Before ICT midnight", ONE_SECOND_EARLIER);
        String onTime = member("At ICT midnight", ICT_MIDNIGHT);
        pay(early, 7001, 11_000, ONE_SECOND_EARLIER);
        pay(onTime, 7002, 99_000, ICT_MIDNIGHT);

        var collections = repository.collections(
                repository.payments(null, null, null, "ALL", PERIOD, "PAID", NOW));
        assertEquals(2, collections.size(), "hai khoản phải rơi vào hai ngày ICT khác nhau");
        assertEquals(LocalDate.of(2026, 9, 30), collections.get(0).date());
        assertEquals(11_000, collections.get(0).amount());
        assertEquals(LocalDate.of(2026, 10, 1), collections.get(1).date(),
                "khoản thu lúc 17:00Z thuộc ngày 01/10 giờ Việt Nam, không phải 30/09 giờ UTC");
        assertEquals(99_000, collections.get(1).amount());

        var registrations = repository.registrations(PERIOD);
        assertEquals(1L, registrations.get(LocalDate.of(2026, 9, 30)));
        assertEquals(1L, registrations.get(LocalDate.of(2026, 10, 1)),
                "người dùng tạo lúc 17:00Z cũng phải đếm vào ngày 01/10 giờ Việt Nam");
    }

    /**
     * Khoá thẳng bất biến khiến bài kiểm trên không phụ thuộc máy chạy: ép múi giờ phiên của DB sang
     * vài múi khác nhau, khóa ngày vẫn phải ra cùng một ngày giờ Việt Nam. Đây là thứ mà cách viết cũ
     * {@code cast(x at time zone 'Asia/Ho_Chi_Minh' as date)} không đạt được — nó cắt ngày theo múi
     * giờ phiên, nên đổi về cách viết đó là bài này đỏ trên mọi máy, không chỉ trên CI chạy UTC.
     *
     * <p>Dùng kết nối riêng chứ không mượn kết nối của pool, vì {@code set time zone} còn dính lại
     * trên kết nối khi nó được trả về pool.
     */
    @Test
    void theIctDayKeyIgnoresWhateverZoneTheDatabaseSessionRunsIn() throws Exception {
        var hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        try (Connection connection = DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sessionZone : new String[]{"UTC", "Asia/Ho_Chi_Minh", "Pacific/Kiritimati", "Pacific/Niue"}) {
                statement.execute("set time zone '" + sessionZone + "'");
                assertEquals(20_261_001L, dayKeyOf(statement, ICT_MIDNIGHT),
                        () -> "17:00Z là 00:00 ngày 01/10 giờ Việt Nam, phiên DB đang ở " + sessionZone);
                assertEquals(20_260_930L, dayKeyOf(statement, ONE_SECOND_EARLIER),
                        () -> "trước đó một giây vẫn là 30/09 giờ Việt Nam, phiên DB đang ở " + sessionZone);
            }
        }
    }

    private long dayKeyOf(Statement statement, OffsetDateTime instant) throws Exception {
        String literal = "timestamp with time zone '" + instant.toInstant() + "'";
        try (ResultSet row = statement.executeQuery("select " + AdminQuery.dayKey(literal) + " day_bucket")) {
            row.next();
            return row.getLong("day_bucket");
        }
    }

    private String member(String name, OffsetDateTime createdAt) {
        var user = new UserEntity();
        user.setPhone("+8498" + Math.abs(name.hashCode() % 1_000_000));
        user.setDisplayName(name);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        String id = users.saveAndFlush(user).getId();
        jdbc.update("update app.users set created_at=? where id=?", createdAt, id);
        return id;
    }

    private void pay(String user, long code, int amount, OffsetDateTime paidAt) {
        var order = new PaymentOrderEntity(code, user, PlanTier.PLAN_99K, amount, "timezone test");
        order.setSource(PaymentSource.REAL);
        order.setStatus(PaymentOrderStatus.PAID);
        order.setPaidAt(paidAt);
        payments.saveAndFlush(order);
    }
}
