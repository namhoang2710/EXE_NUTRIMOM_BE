package vn.nutrimom.consultation.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ExpertDayOffEntity;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.SlotGrid;
import vn.nutrimom.consultation.domain.SlotState;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.domain.UnavailableReason;
import vn.nutrimom.consultation.dto.SlotDtos.AvailabilitySlot;
import vn.nutrimom.consultation.dto.SlotDtos.BookingBrief;
import vn.nutrimom.consultation.dto.SlotDtos.DayAvailabilityResponse;
import vn.nutrimom.consultation.dto.SlotDtos.DayScheduleResponse;
import vn.nutrimom.consultation.dto.SlotDtos.DaySummary;
import vn.nutrimom.consultation.dto.SlotDtos.ScheduleSlot;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ExpertDayOffRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/**
 * Lịch làm việc của chuyên gia. Mỗi ngày mặc định mở đủ lưới {@link SlotGrid}; chuyên gia
 * đóng từng khung giờ (một dòng {@code CLOSED}) hoặc nghỉ trọn ngày (một dòng
 * {@code expert_day_offs}). Hai cơ chế tách rời nhau nên tắt "nghỉ cả ngày" trả lại đúng
 * những khung giờ đã đóng tay trước đó.
 */
@Service
public class ExpertScheduleService {
    /** Chặn quét quá dài cho bảng tổng hợp theo ngày. */
    private static final long MAX_SUMMARY_DAYS = 31;

    private final AvailabilitySlotRepository slots;
    private final ExpertDayOffRepository dayOffs;
    private final ExpertProfileRepository experts;
    private final ConsultationRequestRepository requests;
    private final UserRepository users;
    private final Clock clock;

    public ExpertScheduleService(AvailabilitySlotRepository slots,
                                 ExpertDayOffRepository dayOffs,
                                 ExpertProfileRepository experts,
                                 ConsultationRequestRepository requests,
                                 UserRepository users,
                                 Clock clock) {
        this.slots = slots;
        this.dayOffs = dayOffs;
        this.experts = experts;
        this.requests = requests;
        this.users = users;
        this.clock = clock;
    }

    // ----- User -----

    /** Lịch một ngày của chuyên gia: đủ mọi mốc, ô không đặt được kèm lý do. */
    @Transactional(readOnly = true)
    public DayAvailabilityResponse availabilityForUser(String expertUserId, LocalDate date) {
        requireActiveExpert(expertUserId);
        requireBookableDate(date);
        boolean dayOff = dayOffs.existsByExpertUserIdAndOffDate(expertUserId, date);
        Map<LocalTime, SlotStatus> taken = takenByStart(expertUserId, date);
        LocalDateTime now = ConsultationClock.nowVietnam(clock);

        List<AvailabilitySlot> cells = new ArrayList<>();
        for (LocalTime start : SlotGrid.startTimes()) {
            UnavailableReason reason = reasonFor(date, start, now, dayOff, taken.get(start));
            cells.add(new AvailabilitySlot(start, SlotGrid.endOf(start), reason == null, reason));
        }
        return new DayAvailabilityResponse(date, dayOff, cells);
    }

    // ----- Chuyên gia -----

    /** Lịch làm việc một ngày của chính chuyên gia, kèm tên khách trên các ô đã đặt. */
    @Transactional(readOnly = true)
    public DayScheduleResponse scheduleForExpert(String expertUserId, LocalDate date) {
        requireExpert(expertUserId);
        requireBookableDate(date);
        return buildSchedule(expertUserId, date);
    }

    /**
     * Gạt đóng/mở một khung giờ. Trả về đúng ô vừa đổi để FE cập nhật tại chỗ, không phải
     * tải lại cả ngày.
     */
    @Transactional
    public ScheduleSlot setSlotClosed(String expertUserId, LocalDate date, LocalTime startTime,
                                      boolean closed) {
        requireExpert(expertUserId);
        requireBookableDate(date);
        requireGridStart(startTime);
        LocalDateTime now = ConsultationClock.nowVietnam(clock);
        if (!date.atTime(startTime).isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khung giờ đã trôi qua, không thể thay đổi.");
        }

        Optional<AvailabilitySlotEntity> existing = slots.findForUpdate(expertUserId, date, startTime);
        if (existing.filter(slot -> slot.getStatus() == SlotStatus.BOOKED).isPresent()) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ này đã có người đặt, không thể đóng hoặc mở lại.");
        }
        if (closed && existing.isEmpty()) {
            close(expertUserId, date, startTime);
        } else if (!closed) {
            existing.ifPresent(slot -> {
                slots.delete(slot);
                slots.flush();
            });
        }
        SlotState state = closed ? SlotState.CLOSED : SlotState.OPEN;
        return new ScheduleSlot(startTime, SlotGrid.endOf(startTime), state, false, null);
    }

    /**
     * Bật/tắt nghỉ trọn ngày. Không đụng tới các ô đã đặt (buổi đã hẹn vẫn giữ nguyên) và
     * không đụng tới các ô chuyên gia đã đóng tay.
     */
    @Transactional
    public DayScheduleResponse setDayOff(String expertUserId, LocalDate date, boolean dayOff) {
        requireExpert(expertUserId);
        requireBookableDate(date);
        boolean current = dayOffs.existsByExpertUserIdAndOffDate(expertUserId, date);
        if (dayOff && !current) {
            ExpertDayOffEntity entity = new ExpertDayOffEntity();
            entity.setExpertUserId(expertUserId);
            entity.setOffDate(date);
            try {
                dayOffs.saveAndFlush(entity);
            } catch (DataIntegrityViolationException ex) {
                // Một request song song vừa bật nghỉ cả ngày cho đúng ngày này. Không nuốt lỗi:
                // flush hỏng đã đánh dấu transaction rollback-only, chạy tiếp sẽ nổ ở lúc commit.
                throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                        "Lịch ngày này vừa được cập nhật ở nơi khác. Vui lòng tải lại.");
            }
        } else if (!dayOff && current) {
            dayOffs.deleteByExpertUserIdAndOffDate(expertUserId, date);
            dayOffs.flush();
        }
        return buildSchedule(expertUserId, date);
    }

    /** Tổng hợp theo ngày cho dải ngày điều hướng của chuyên gia. */
    @Transactional(readOnly = true)
    public List<DaySummary> summary(String expertUserId, LocalDate from, LocalDate to) {
        requireExpert(expertUserId);
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Khoảng ngày không hợp lệ.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_SUMMARY_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khoảng ngày tối đa là " + MAX_SUMMARY_DAYS + " ngày.");
        }
        Map<LocalDate, List<AvailabilitySlotEntity>> byDate = slots
                .findByExpertUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        expertUserId, from, to)
                .stream()
                .collect(Collectors.groupingBy(AvailabilitySlotEntity::getSlotDate));
        Set<LocalDate> offDates = dayOffs
                .findByExpertUserIdAndOffDateBetween(expertUserId, from, to).stream()
                .map(ExpertDayOffEntity::getOffDate)
                .collect(Collectors.toSet());
        LocalDateTime now = ConsultationClock.nowVietnam(clock);

        List<DaySummary> out = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Map<LocalTime, SlotStatus> taken = indexByStart(byDate.getOrDefault(date, List.of()));
            boolean off = offDates.contains(date);
            int open = 0;
            int booked = 0;
            int closed = 0;
            for (LocalTime start : SlotGrid.startTimes()) {
                SlotStatus status = taken.get(start);
                if (status == SlotStatus.BOOKED) {
                    booked++;
                } else if (status == SlotStatus.CLOSED) {
                    closed++;
                } else if (reasonFor(date, start, now, off, null) == null) {
                    open++;
                }
            }
            out.add(new DaySummary(date, open, booked, closed, off));
        }
        return out;
    }

    // ----- Helpers -----

    private void close(String expertUserId, LocalDate date, LocalTime startTime) {
        AvailabilitySlotEntity slot = new AvailabilitySlotEntity();
        slot.setExpertUserId(expertUserId);
        slot.setSlotDate(date);
        slot.setStartTime(startTime);
        slot.setEndTime(SlotGrid.endOf(startTime));
        slot.setStatus(SlotStatus.CLOSED);
        try {
            slots.saveAndFlush(slot);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ vừa có người đặt. Vui lòng tải lại lịch.");
        }
    }

    private DayScheduleResponse buildSchedule(String expertUserId, LocalDate date) {
        boolean dayOff = dayOffs.existsByExpertUserIdAndOffDate(expertUserId, date);
        List<AvailabilitySlotEntity> rows =
                slots.findByExpertUserIdAndSlotDateOrderByStartTimeAsc(expertUserId, date);
        Map<LocalTime, AvailabilitySlotEntity> byStart = new HashMap<>();
        rows.forEach(row -> byStart.put(row.getStartTime(), row));
        Map<String, BookingBrief> briefs = bookingBriefs(rows);
        LocalDateTime now = ConsultationClock.nowVietnam(clock);

        List<ScheduleSlot> cells = new ArrayList<>();
        boolean hasBookings = false;
        for (LocalTime start : SlotGrid.startTimes()) {
            AvailabilitySlotEntity row = byStart.get(start);
            SlotState state = SlotState.OPEN;
            BookingBrief booking = null;
            if (row != null && row.getStatus() == SlotStatus.BOOKED) {
                state = SlotState.BOOKED;
                booking = briefs.get(row.getId());
                hasBookings = true;
            } else if (row != null) {
                state = SlotState.CLOSED;
            }
            boolean past = !date.atTime(start).isAfter(now);
            cells.add(new ScheduleSlot(start, SlotGrid.endOf(start), state, past, booking));
        }
        return new DayScheduleResponse(date, dayOff, hasBookings, cells);
    }

    /** Tên khách cho các ô đã đặt, gom trong hai truy vấn để không N+1 theo từng ô. */
    private Map<String, BookingBrief> bookingBriefs(List<AvailabilitySlotEntity> rows) {
        List<String> bookedSlotIds = rows.stream()
                .filter(row -> row.getStatus() == SlotStatus.BOOKED)
                .map(AvailabilitySlotEntity::getId)
                .toList();
        if (bookedSlotIds.isEmpty()) {
            return Map.of();
        }
        List<ConsultationRequestEntity> found = requests.findBySlotIdIn(bookedSlotIds);
        List<String> userIds = found.stream()
                .map(ConsultationRequestEntity::getUserId)
                .distinct()
                .toList();
        Map<String, String> names = new HashMap<>();
        for (UserEntity user : users.findAllById(userIds)) {
            names.put(user.getId(), user.getDisplayName());
        }
        Map<String, BookingBrief> briefs = new HashMap<>();
        for (ConsultationRequestEntity request : found) {
            briefs.put(request.getSlotId(),
                    new BookingBrief(request.getId(), names.get(request.getUserId())));
        }
        return briefs;
    }

    private Map<LocalTime, SlotStatus> takenByStart(String expertUserId, LocalDate date) {
        return indexByStart(
                slots.findByExpertUserIdAndSlotDateOrderByStartTimeAsc(expertUserId, date));
    }

    private static Map<LocalTime, SlotStatus> indexByStart(List<AvailabilitySlotEntity> rows) {
        Map<LocalTime, SlotStatus> byStart = new HashMap<>();
        rows.forEach(row -> byStart.put(row.getStartTime(), row.getStatus()));
        return byStart;
    }

    /** Thứ tự ưu tiên: đã qua giờ, nghỉ cả ngày, đã có người đặt, chuyên gia đóng. */
    private static UnavailableReason reasonFor(LocalDate date, LocalTime start, LocalDateTime now,
                                               boolean dayOff, SlotStatus taken) {
        if (!date.atTime(start).isAfter(now)) {
            return UnavailableReason.PAST;
        }
        if (dayOff) {
            return UnavailableReason.DAY_OFF;
        }
        if (taken == SlotStatus.BOOKED) {
            return UnavailableReason.BOOKED;
        }
        if (taken == SlotStatus.CLOSED) {
            return UnavailableReason.CLOSED;
        }
        return null;
    }

    private void requireBookableDate(LocalDate date) {
        if (!SlotGrid.isWithinHorizon(date, ConsultationClock.nowVietnam(clock).toLocalDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ xem và đặt lịch được trong vòng " + SlotGrid.HORIZON_DAYS + " ngày tới.");
        }
    }

    private static void requireGridStart(LocalTime startTime) {
        if (!SlotGrid.isValidStart(startTime)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khung giờ không hợp lệ. Lịch chia theo từng " + SlotGrid.SLOT_MINUTES
                            + " phút, từ " + SlotGrid.OPENING + " đến " + SlotGrid.CLOSING + ".");
        }
    }

    private ExpertProfileEntity requireExpert(String expertUserId) {
        return experts.findById(expertUserId).orElseThrow(() -> new BusinessException(
                ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa phải hồ sơ chuyên gia."));
    }

    private ExpertProfileEntity requireActiveExpert(String expertUserId) {
        return experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy chuyên gia."));
    }
}
