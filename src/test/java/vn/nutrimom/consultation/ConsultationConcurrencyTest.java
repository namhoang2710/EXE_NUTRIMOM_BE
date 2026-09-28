package vn.nutrimom.consultation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;
import vn.nutrimom.consultation.service.ConsultationRequestService;
import vn.nutrimom.consultation.service.ExpertScheduleService;

/**
 * Hai người dùng cùng chiếm một khung giờ tại cùng thời điểm.
 *
 * <p>Cố ý <strong>không</strong> đánh dấu {@code @Transactional} ở lớp test: mỗi luồng phải
 * chạy trong transaction riêng và commit thật thì mới dựng được tình huống tranh chấp. Chốt
 * chặn được kiểm ở đây là unique index {@code (expert_user_id, slot_date, start_time)} — khóa
 * bi quan không giữ được một dòng chưa tồn tại, nên chỉ ràng buộc ở tầng DB mới phân xử được.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsultationConcurrencyTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalTime CONTESTED_SLOT = LocalTime.of(15, 0);
    private static final int RACERS = 8;

    @Autowired ConsultationRequestService requestService;
    @Autowired ExpertScheduleService scheduleService;
    @Autowired UserRepository users;
    @Autowired ExpertProfileRepository experts;
    @Autowired AvailabilitySlotRepository slots;
    @Autowired ConsultationRequestRepository requests;

    private String expertId;
    private final List<String> userIds = new ArrayList<>();
    private LocalDate date;

    @BeforeEach
    void setUp() {
        date = LocalDate.now(VN).plusDays(7);
        expertId = createUser("0999000001", "BS Race");
        ExpertProfileEntity profile = new ExpertProfileEntity();
        profile.setUserId(expertId);
        profile.setFullName("BS Race");
        profile.setSpecialty(Specialty.HEALTH);
        experts.saveAndFlush(profile);

        for (int i = 0; i < RACERS; i++) {
            userIds.add(createUser("09990010%02d".formatted(i), "Racer " + i));
        }
    }

    @AfterEach
    void tearDown() {
        requests.deleteAll(requests.findByUserIdOrderByCreatedAtDesc(expertId));
        userIds.forEach(id -> requests.deleteAll(requests.findByUserIdOrderByCreatedAtDesc(id)));
        slots.deleteAll(slots.findByExpertUserIdAndSlotDateOrderByStartTimeAsc(expertId, date));
        experts.deleteById(expertId);
        users.deleteAllById(userIds);
        users.deleteById(expertId);
    }

    @Test
    void onlyOneOfManySimultaneousBookingsWinsTheSameSlot() throws Exception {
        List<Object> outcomes = runSimultaneously(userId -> requestService.create(userId,
                new CreateConsultationRequest(AssignmentType.DIRECT, expertId, date,
                        CONTESTED_SLOT, null, null)));

        long booked = outcomes.stream().filter(o -> !(o instanceof Throwable)).count();
        List<Throwable> failures = outcomes.stream()
                .filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();

        assertThat(booked)
                .as("đúng một người đặt được khung giờ tranh chấp")
                .isEqualTo(1);
        assertThat(failures)
                .as("những người còn lại đều bị từ chối bằng SLOT_UNAVAILABLE, không phải lỗi hệ thống")
                .allSatisfy(failure -> {
                    assertThat(failure).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) failure).getCode())
                            .isEqualTo(ErrorCode.SLOT_UNAVAILABLE.code());
                });
        assertThat(slots.findByExpertUserIdAndSlotDateOrderByStartTimeAsc(expertId, date))
                .as("chỉ một dòng được ghi cho khung giờ đó")
                .hasSize(1);
    }

    @Test
    void bookingAndClosingTheSameSlotCannotBothSucceed() throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> booking = pool.submit(attempt(gate, () -> requestService.create(
                    userIds.get(0), new CreateConsultationRequest(AssignmentType.DIRECT, expertId,
                            date, CONTESTED_SLOT, null, null))));
            Future<Object> closing = pool.submit(attempt(gate, () ->
                    scheduleService.setSlotClosed(expertId, date, CONTESTED_SLOT, true)));

            Object bookingOutcome = booking.get(20, TimeUnit.SECONDS);
            Object closingOutcome = closing.get(20, TimeUnit.SECONDS);

            long succeeded = List.of(bookingOutcome, closingOutcome).stream()
                    .filter(o -> !(o instanceof Throwable)).count();
            assertThat(succeeded)
                    .as("không thể vừa đặt được vừa đóng được cùng một khung giờ")
                    .isEqualTo(1);
            assertThat(slots.findByExpertUserIdAndSlotDateOrderByStartTimeAsc(expertId, date))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Chạy {@code action} đồng thời cho mọi user, trả kết quả hoặc exception theo thứ tự. */
    private List<Object> runSimultaneously(ThrowingAction action) throws Exception {
        CyclicBarrier gate = new CyclicBarrier(RACERS);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String userId : userIds) {
                futures.add(pool.submit(attempt(gate, () -> action.run(userId))));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Đợi mọi luồng cùng tới vạch xuất phát rồi mới chạy, để tranh chấp là thật. */
    private static Callable<Object> attempt(CyclicBarrier gate, ThrowingSupplier body) {
        return () -> {
            gate.await(20, TimeUnit.SECONDS);
            try {
                return body.get();
            } catch (Throwable failure) {
                return failure;
            }
        };
    }

    private String createUser(String phone, String displayName) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run(String userId) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
