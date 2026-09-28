package vn.nutrimom.consultation;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.SlotGrid;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsultationIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Số khung giờ trong một ngày: 08:00–20:00, mỗi khung 30 phút. */
    private static final int SLOTS_PER_DAY = 24;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;

    @Test
    void adminCreatesExpertThenUserDiscoversAndSeesSpecialties() throws Exception {
        String expertId = createExpert("0912000001", "PSYCHOLOGY", "BS Tam Ly");
        String userId = createUserAccount("0912000002", "Mom A");

        mockMvc.perform(get("/api/v1/experts").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].user_id").value(expertId))
                .andExpect(jsonPath("$.data[0].full_name").value("BS Tam Ly"))
                .andExpect(jsonPath("$.data[0].specialty").value("PSYCHOLOGY"))
                .andExpect(jsonPath("$.data[0].average_rating").value(0));

        mockMvc.perform(get("/api/v1/experts/{id}", expertId).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.years_of_experience").value(5));

        mockMvc.perform(get("/api/v1/reference-data/specialties").with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value("PSYCHOLOGY"));
    }

    @Test
    void newExpertHasFullDefaultGridWithoutOpeningAnything() throws Exception {
        String expertId = createExpert("0912000101", "HEALTH", "BS Grid");
        String userId = createUserAccount("0912000102", "Mom Grid");
        LocalDate date = daysFromToday(1);

        availability(userId, expertId, date)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.day_off").value(false))
                .andExpect(jsonPath("$.data.slots.length()").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data.slots[0].start_time").value("08:00:00"))
                .andExpect(jsonPath("$.data.slots[0].end_time").value("08:30:00"))
                .andExpect(jsonPath("$.data.slots[0].available").value(true))
                .andExpect(jsonPath("$.data.slots[0].reason").doesNotExist())
                .andExpect(jsonPath("$.data.slots[23].start_time").value("19:30:00"))
                .andExpect(jsonPath("$.data.slots[23].end_time").value("20:00:00"))
                .andExpect(jsonPath("$.data.slots[23].available").value(true));

        // Chuyên gia cũng thấy đủ lưới, tất cả đang mở.
        mockMvc.perform(get("/api/v1/expert/schedule").param("date", date.toString())
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has_bookings").value(false))
                .andExpect(jsonPath("$.data.slots.length()").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data.slots[0].state").value("OPEN"))
                .andExpect(jsonPath("$.data.slots[0].booking").doesNotExist());
    }

    @Test
    void todayKeepsPastSlotsInGridMarkedAsPast() throws Exception {
        // Trước 08:30 giờ VN thì chưa có khung nào trôi qua, không kiểm được.
        assumeTrue(LocalTime.now(VN).isAfter(LocalTime.of(8, 30)),
                "Cần chạy sau 08:30 giờ VN để có khung giờ đã trôi qua.");
        String expertId = createExpert("0912000111", "HEALTH", "BS Today");
        String userId = createUserAccount("0912000112", "Mom Today");

        availability(userId, expertId, LocalDate.now(VN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slots.length()").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data.slots[0].available").value(false))
                .andExpect(jsonPath("$.data.slots[0].reason").value("PAST"));
    }

    @Test
    void closedSlotStaysVisibleWithReasonAndCannotBeBooked() throws Exception {
        String expertId = createExpert("0912000121", "OBSTETRICS", "BS Close");
        String userId = createUserAccount("0912000122", "Mom Close");
        LocalDate date = daysFromToday(2);

        closeSlot(expertId, date, "09:00:00", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CLOSED"));

        availability(userId, expertId, date)
                .andExpect(jsonPath("$.data.slots.length()").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data.slots[%d].available".formatted(index("09:00"))).value(false))
                .andExpect(jsonPath("$.data.slots[%d].reason".formatted(index("09:00"))).value("CLOSED"));

        bookDirect(userId, expertId, date, "09:00:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_UNAVAILABLE"));

        // Mở lại thì ô trở lại bình thường.
        closeSlot(expertId, date, "09:00:00", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OPEN"));
        availability(userId, expertId, date)
                .andExpect(jsonPath("$.data.slots[%d].available".formatted(index("09:00"))).value(true));
    }

    @Test
    void togglingTheSameSlotTwiceIsIdempotent() throws Exception {
        String expertId = createExpert("0912000131", "HEALTH", "BS Idem");
        LocalDate date = daysFromToday(3);

        closeSlot(expertId, date, "10:00:00", true).andExpect(status().isOk());
        closeSlot(expertId, date, "10:00:00", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CLOSED"));
        closeSlot(expertId, date, "10:00:00", false).andExpect(status().isOk());
        closeSlot(expertId, date, "10:00:00", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OPEN"));

        mockMvc.perform(get("/api/v1/expert/schedule").param("date", date.toString())
                        .with(expertJwt(expertId)))
                .andExpect(jsonPath("$.data.slots[%d].state".formatted(index("10:00"))).value("OPEN"));
    }

    @Test
    void directBookingOccupiesSlotAndRejectsDoubleBooking() throws Exception {
        String expertId = createExpert("0912000011", "OBSTETRICS", "BS San Khoa");
        String user1 = createUserAccount("0912000012", "Mom 1");
        String user2 = createUserAccount("0912000013", "Mom 2");
        LocalDate date = daysFromToday(1);

        bookDirect(user1, expertId, date, "09:00:00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONSULTATION"))
                .andExpect(jsonPath("$.data.slot.slot_date").value(date.toString()))
                .andExpect(jsonPath("$.data.slot.start_time").value("09:00:00"));

        // Ô vẫn nằm trong lưới nhưng không chọn được nữa.
        availability(user1, expertId, date)
                .andExpect(jsonPath("$.data.slots.length()").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data.slots[%d].available".formatted(index("09:00"))).value(false))
                .andExpect(jsonPath("$.data.slots[%d].reason".formatted(index("09:00"))).value("BOOKED"));

        bookDirect(user2, expertId, date, "09:00:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_UNAVAILABLE"));

        // Khung giờ khác trong cùng ngày vẫn đặt được.
        bookDirect(user2, expertId, date, "09:30:00").andExpect(status().isCreated());
    }

    @Test
    void expertSeesCustomerOnBookedSlotAndCannotCloseIt() throws Exception {
        String expertId = createExpert("0912000141", "HEALTH", "BS Booked");
        String userId = createUserAccount("0912000142", "Mom Booked");
        LocalDate date = daysFromToday(2);

        bookDirect(userId, expertId, date, "14:00:00").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/expert/schedule").param("date", date.toString())
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has_bookings").value(true))
                .andExpect(jsonPath("$.data.slots[%d].state".formatted(index("14:00"))).value("BOOKED"))
                .andExpect(jsonPath("$.data.slots[%d].booking.user_display_name".formatted(index("14:00")))
                        .value("Mom Booked"));

        closeSlot(expertId, date, "14:00:00", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_UNAVAILABLE"));
    }

    @Test
    void dayOffBlocksWholeDayAndTogglingBackKeepsManuallyClosedSlots() throws Exception {
        String expertId = createExpert("0912000151", "PSYCHOLOGY", "BS DayOff");
        String userId = createUserAccount("0912000152", "Mom DayOff");
        LocalDate date = daysFromToday(4);

        closeSlot(expertId, date, "12:00:00", true).andExpect(status().isOk());
        closeSlot(expertId, date, "12:30:00", true).andExpect(status().isOk());

        setDayOff(expertId, date, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.day_off").value(true))
                .andExpect(jsonPath("$.data.has_bookings").value(false));

        availability(userId, expertId, date)
                .andExpect(jsonPath("$.data.day_off").value(true))
                .andExpect(jsonPath("$.data.slots.length()").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data.slots[%d].reason".formatted(index("09:00"))).value("DAY_OFF"))
                .andExpect(jsonPath("$.data.slots[%d].available".formatted(index("09:00"))).value(false));

        bookDirect(userId, expertId, date, "09:00:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_UNAVAILABLE"));

        // Tắt nghỉ cả ngày: các khung đóng tay phải còn nguyên, phần còn lại mở lại.
        setDayOff(expertId, date, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.day_off").value(false))
                .andExpect(jsonPath("$.data.slots[%d].state".formatted(index("12:00"))).value("CLOSED"))
                .andExpect(jsonPath("$.data.slots[%d].state".formatted(index("12:30"))).value("CLOSED"))
                .andExpect(jsonPath("$.data.slots[%d].state".formatted(index("09:00"))).value("OPEN"));

        availability(userId, expertId, date)
                .andExpect(jsonPath("$.data.slots[%d].reason".formatted(index("12:00"))).value("CLOSED"))
                .andExpect(jsonPath("$.data.slots[%d].available".formatted(index("09:00"))).value(true));
    }

    @Test
    void dayOffKeepsExistingAppointmentsAndFlagsThem() throws Exception {
        String expertId = createExpert("0912000161", "HEALTH", "BS Keep");
        String userId = createUserAccount("0912000162", "Mom Keep");
        LocalDate date = daysFromToday(5);

        bookDirect(userId, expertId, date, "15:00:00").andExpect(status().isCreated());

        setDayOff(expertId, date, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.day_off").value(true))
                .andExpect(jsonPath("$.data.has_bookings").value(true))
                .andExpect(jsonPath("$.data.slots[%d].state".formatted(index("15:00"))).value("BOOKED"));

        mockMvc.perform(get("/api/v1/expert/consultation-requests").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1));
    }

    @Test
    void cancellingReleasesTheSlotForSomeoneElse() throws Exception {
        String expertId = createExpert("0912000171", "OBSTETRICS", "BS Cancel");
        String user1 = createUserAccount("0912000172", "Mom Cancel 1");
        String user2 = createUserAccount("0912000173", "Mom Cancel 2");
        LocalDate date = daysFromToday(3);

        MvcResult created = bookDirect(user1, expertId, date, "16:00:00")
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(post("/api/v1/consultation-requests/{id}/cancel", requestId)
                        .with(userJwt(user1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        availability(user2, expertId, date)
                .andExpect(jsonPath("$.data.slots[%d].available".formatted(index("16:00"))).value(true));
        bookDirect(user2, expertId, date, "16:00:00").andExpect(status().isCreated());
    }

    @Test
    void bookingOffGridOrOutsideHorizonIsRejected() throws Exception {
        String expertId = createExpert("0912000181", "HEALTH", "BS Bounds");
        String userId = createUserAccount("0912000182", "Mom Bounds");

        bookDirect(userId, expertId, daysFromToday(1), "08:15:00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        bookDirect(userId, expertId, daysFromToday(1), "20:00:00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        bookDirect(userId, expertId, daysFromToday(SlotGrid.HORIZON_DAYS + 1), "09:00:00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        availability(userId, expertId, daysFromToday(SlotGrid.HORIZON_DAYS + 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void scheduleSummaryCountsOpenBookedAndClosedPerDay() throws Exception {
        String expertId = createExpert("0912000191", "HEALTH", "BS Summary");
        String userId = createUserAccount("0912000192", "Mom Summary");
        LocalDate from = daysFromToday(1);
        LocalDate to = daysFromToday(3);

        bookDirect(userId, expertId, from, "09:00:00").andExpect(status().isCreated());
        closeSlot(expertId, from, "10:00:00", true).andExpect(status().isOk());
        setDayOff(expertId, to, true).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/expert/schedule/summary")
                        .param("from", from.toString()).param("to", to.toString())
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].date").value(from.toString()))
                .andExpect(jsonPath("$.data[0].booked_count").value(1))
                .andExpect(jsonPath("$.data[0].closed_count").value(1))
                .andExpect(jsonPath("$.data[0].open_count").value(SLOTS_PER_DAY - 2))
                .andExpect(jsonPath("$.data[1].open_count").value(SLOTS_PER_DAY))
                .andExpect(jsonPath("$.data[2].day_off").value(true))
                .andExpect(jsonPath("$.data[2].open_count").value(0));
    }

    @Test
    void randomFlowPoolAcceptCompleteThenReviewUpdatesAverage() throws Exception {
        String expertId = createExpert("0912000021", "HEALTH", "BS Suc Khoe");
        String userId = createUserAccount("0912000022", "Mom R");
        LocalDate date = daysFromToday(2);

        MvcResult created = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"RANDOM","specialty":"HEALTH","note":"xin tu van"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_EXPERT"))
                .andReturn();
        String requestId = readData(created, "/data/id");

        mockMvc.perform(get("/api/v1/expert/consultation-requests")
                        .param("type", "pool").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(requestId));

        acceptRequest(expertId, requestId, date, "10:00:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONSULTATION"))
                .andExpect(jsonPath("$.data.slot.start_time").value("10:00:00"));

        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/complete", requestId)
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/consultation-requests/{id}/review", requestId)
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"tot\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").value(4));

        mockMvc.perform(get("/api/v1/experts/{id}", expertId).with(userJwt(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.average_rating").value(4.0))
                .andExpect(jsonPath("$.data.rating_count").value(1));

        // Đánh giá lần hai -> 409
        mockMvc.perform(post("/api/v1/consultation-requests/{id}/review", requestId)
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"));

        // Chuyên gia xem được đầy đủ review
        mockMvc.perform(get("/api/v1/expert/reviews").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].rating").value(4))
                .andExpect(jsonPath("$.data.items[0].comment").value("tot"));

        // Lọc review theo số sao
        mockMvc.perform(get("/api/v1/expert/reviews").param("rating", "4")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1));
        mockMvc.perform(get("/api/v1/expert/reviews").param("rating", "5")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(0));
        // Lọc chỉ đánh giá có nhận xét
        mockMvc.perform(get("/api/v1/expert/reviews").param("has_comment", "true")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1));
    }

    @Test
    void assignedListSortsByAppointmentAndFiltersHistory() throws Exception {
        String expertId = createExpert("0912000071", "OBSTETRICS", "BS G");
        String user1 = createUserAccount("0912000072", "Alice");
        String user2 = createUserAccount("0912000073", "Bob");
        LocalDate later = daysFromToday(4);
        LocalDate earlier = daysFromToday(3);

        MvcResult createdLater = bookDirect(user1, expertId, later, "09:00:00")
                .andExpect(status().isCreated()).andReturn();
        String reqLater = readData(createdLater, "/data/id");
        bookDirect(user2, expertId, earlier, "09:00:00").andExpect(status().isCreated());

        // Mặc định PENDING_CONSULTATION, sắp theo giờ hẹn tăng dần
        mockMvc.perform(get("/api/v1/expert/consultation-requests").with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(2))
                .andExpect(jsonPath("$.data.items[0].slot.slot_date").value(earlier.toString()))
                .andExpect(jsonPath("$.data.items[1].slot.slot_date").value(later.toString()));

        // Tìm theo tên user
        mockMvc.perform(get("/api/v1/expert/consultation-requests").param("q", "ali")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].user_display_name").value("Alice"));

        // Hoàn thành 1 buổi rồi lọc lịch sử COMPLETED
        mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/complete", reqLater)
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expert/consultation-requests").param("status", "COMPLETED")
                        .with(expertJwt(expertId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_items").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(reqLater));
    }

    @Test
    void secondExpertAcceptIsRejectedAsAlreadyClaimed() throws Exception {
        String expertA = createExpert("0912000031", "PSYCHOLOGY", "BS A");
        String expertB = createExpert("0912000032", "PSYCHOLOGY", "BS B");
        String userId = createUserAccount("0912000033", "Mom C");
        LocalDate date = daysFromToday(3);

        MvcResult created = mockMvc.perform(post("/api/v1/consultation-requests")
                        .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assignment_type":"RANDOM","specialty":"PSYCHOLOGY"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = readData(created, "/data/id");

        acceptRequest(expertA, requestId, date, "08:00:00").andExpect(status().isOk());

        acceptRequest(expertB, requestId, date, "08:00:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_ALREADY_CLAIMED"));
    }

    @Test
    void reviewNotAllowedBeforeCompletedAndRowLevelIsolation() throws Exception {
        String expertId = createExpert("0912000041", "HEALTH", "BS D");
        String user1 = createUserAccount("0912000042", "Mom 1");
        String user2 = createUserAccount("0912000043", "Mom 2");

        MvcResult created = bookDirect(user1, expertId, daysFromToday(4), "14:00:00")
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = readData(created, "/data/id");

        // Chưa hoàn thành -> không được đánh giá
        mockMvc.perform(post("/api/v1/consultation-requests/{id}/review", requestId)
                        .with(userJwt(user1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_NOT_ALLOWED"));

        // User khác không thấy request này -> 404
        mockMvc.perform(get("/api/v1/consultation-requests/{id}", requestId).with(userJwt(user2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ----- helpers -----

    /** Vị trí của một mốc "HH:mm" trong mảng 24 khung giờ trả về. */
    private static int index(String startTime) {
        return SlotGrid.startTimes().indexOf(LocalTime.parse(startTime));
    }

    private static LocalDate daysFromToday(int days) {
        return LocalDate.now(VN).plusDays(days);
    }

    private ResultActions availability(String userId, String expertUserId, LocalDate date)
            throws Exception {
        return mockMvc.perform(get("/api/v1/experts/{id}/availability", expertUserId)
                .param("date", date.toString()).with(userJwt(userId)));
    }

    private ResultActions closeSlot(String expertUserId, LocalDate date, String startTime,
                                    boolean closed) throws Exception {
        return mockMvc.perform(put("/api/v1/expert/schedule/slot")
                .with(expertJwt(expertUserId)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"slot_date":"%s","start_time":"%s","closed":%s}
                        """.formatted(date, startTime, closed)));
    }

    private ResultActions setDayOff(String expertUserId, LocalDate date, boolean dayOff)
            throws Exception {
        return mockMvc.perform(put("/api/v1/expert/schedule/day-off")
                .with(expertJwt(expertUserId)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"slot_date":"%s","day_off":%s}
                        """.formatted(date, dayOff)));
    }

    private ResultActions bookDirect(String userId, String expertUserId, LocalDate date,
                                     String startTime) throws Exception {
        return mockMvc.perform(post("/api/v1/consultation-requests")
                .with(userJwt(userId)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"assignment_type":"DIRECT","expert_user_id":"%s",
                         "slot_date":"%s","start_time":"%s"}
                        """.formatted(expertUserId, date, startTime)));
    }

    private ResultActions acceptRequest(String expertUserId, String requestId, LocalDate date,
                                        String startTime) throws Exception {
        return mockMvc.perform(post("/api/v1/expert/consultation-requests/{id}/accept", requestId)
                .with(expertJwt(expertUserId)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"slot_date":"%s","start_time":"%s"}
                        """.formatted(date, startTime)));
    }

    private String createExpert(String phone, String specialty, String fullName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/experts")
                        .with(adminJwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","password":"password123","full_name":"%s",
                                 "specialty":"%s","title":"Chuyen gia","workplace":"NutriMom",
                                 "years_of_experience":5}
                                """.formatted(phone, fullName, specialty)))
                .andExpect(status().isCreated())
                .andReturn();
        return readData(result, "/data/user_id");
    }

    private String createUserAccount(String phone, String displayName) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setDisplayName(displayName);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(Set.of(UserRole.USER));
        return users.saveAndFlush(user).getId();
    }

    private String readData(MvcResult result, String pointer) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at(pointer).stringValue();
    }

    private RequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token.subject("admin").claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor userJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private RequestPostProcessor expertJwt(String userId) {
        return jwt().jwt(token -> token.subject(userId).claim("roles", List.of("EXPERT")))
                .authorities(new SimpleGrantedAuthority("ROLE_EXPERT"));
    }
}
