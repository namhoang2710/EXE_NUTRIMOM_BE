package vn.nutrimom.admin.model;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/** Shared cross-field validation for bounded, parameterized admin read queries. */
public final class AdminQuery {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private AdminQuery() {}

    /**
     * SQL gom một cột instant về ngày dương lịch giờ Việt Nam, dưới dạng số nguyên yyyyMMdd — dùng
     * cho {@code group by}/{@code order by}, rồi đọc lại bằng {@link #dayOf(int)}.
     *
     * <p>Không dùng {@code cast(x at time zone 'Asia/Ho_Chi_Minh' as date)}: Postgres cho kết quả
     * đúng và độc lập với múi giờ phiên (vì {@code at time zone} trả về TIMESTAMP không múi giờ rồi
     * mới cắt ngày), nhưng H2 — bộ test chạy trên đó — giữ nguyên TIMESTAMP WITH TIME ZONE và cắt
     * ngày theo múi giờ PHIÊN, vốn thừa kế múi giờ JVM. Trên H2 phép cast ấy do đó tương đương
     * {@code cast(x as date)}: mốc 17:00Z rơi vào 30/09 khi máy chạy UTC thay vì 01/10 giờ Việt Nam.
     * {@code extract} thì tôn trọng offset của chính giá trị trên cả hai, nên cùng một câu SQL cho
     * cùng một ngày ở mọi múi giờ máy chạy. Xem {@code AdminOperationsTimeZoneTest}.
     */
    public static String dayKey(String instantColumn) {
        String local = "(" + instantColumn + " at time zone '" + ZONE.getId() + "')";
        return "cast(extract(year from " + local + ")*10000"
                + "+extract(month from " + local + ")*100"
                + "+extract(day from " + local + ") as bigint)";
    }

    /** Đọc ngược khóa yyyyMMdd mà {@link #dayKey(String)} sinh ra. */
    public static LocalDate dayOf(int key) {
        return LocalDate.of(key / 10000, key / 100 % 100, key % 100);
    }
    public static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }
    public static void page(int page, int size) {
        if (page < 1 || page > 1_000_000) throw invalid("page", "Trang phải trong khoảng 1..1000000.");
        if (size < 1 || size > 100) throw invalid("pageSize", "Số dòng phải trong khoảng 1..100.");
    }
    public static String search(String q) {
        String value = q == null ? "" : q.trim();
        if (value.length() > 100) throw invalid("q", "Từ khóa tối đa 100 ký tự.");
        return value;
    }
    public static String like(String q) {
        return "%" + search(q).toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    public static String option(String field, String value, Set<String> allowed, String fallback) {
        if (value == null) return fallback;
        if (!allowed.contains(value)) throw invalid(field, "Giá trị " + field + " không hợp lệ.");
        return value;
    }
    public static Set<String> statuses(String value) {
        if (value == null) return Set.of();
        var entries = value.split(",", -1);
        if (entries.length > 4 || entries.length == 0) throw invalid("status", "Tối đa bốn trạng thái.");
        Set<String> allowed = Set.of("PENDING_EXPERT", "PENDING_CONSULTATION", "COMPLETED", "CANCELLED");
        for (String entry : entries) option("status", entry, allowed, null);
        return Set.copyOf(Arrays.asList(entries));
    }
    public static String id(String field, String value) {
        if (value == null) return null;
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException e) { throw invalid(field, "Mã UUID không hợp lệ."); }
    }
    public static long orderCode(String value) {
        try {
            if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
            long result = Long.parseLong(value);
            if (result <= 0) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException e) { throw invalid("orderCode", "Mã đơn phải là số nguyên dương hợp lệ."); }
    }
    public static Period period(LocalDate from, LocalDate to, Clock clock, boolean defaultPeriod, boolean future) {
        if ((from == null) != (to == null)) throw invalid("fromDate", "Cần cung cấp cả fromDate và toDate.");
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        if (from == null) {
            if (!defaultPeriod) return null;
            from = today.withDayOfMonth(1); to = today;
        }
        long length = ChronoUnit.DAYS.between(from, to) + 1;
        if (length < 1 || length > 366) throw invalid("toDate", "Khoảng ngày phải từ 1 đến 366 ngày.");
        if (!future && to.isAfter(today)) throw invalid("toDate", "Ngày kết thúc không được vượt hôm nay.");
        if (from.getYear() < 1900 || to.getYear() > 9998) throw invalid("fromDate", "Ngày ngoài phạm vi hỗ trợ.");
        return new Period(from, to, from.minusDays(length), from.minusDays(1));
    }
}
