package vn.nutrimom.nutrition.service;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Hạn thời gian của một lượt tìm kiếm thực đơn.
 *
 * <p>Đo bằng {@link System#nanoTime()} chứ không bằng {@code Clock} của ứng dụng: đây là đo
 * <strong>khoảng thời gian trôi qua</strong>, và đồng hồ treo tường có thể nhảy lùi khi máy đồng bộ
 * NTP — một lượt tìm kiếm không được phép kết thúc sớm hay chạy mãi vì chuyện đó.</p>
 *
 * <p>Chạm hạn <strong>không</strong> có nghĩa là không tồn tại thực đơn phù hợp. Nơi gọi phải nói
 * đúng điều đó với người dùng ({@code SEARCH_LIMIT}, TC19); kết luận "không có món nào hợp với bạn"
 * khi mới chỉ hết thời gian tìm là một lời nói dối rất dễ tin.</p>
 */
public final class SearchDeadline {

    /** Hạn cho thực đơn một ngày. */
    public static final Duration ONE_DAY_BUDGET = Duration.ofSeconds(2);

    /**
     * Hạn cho thực đơn ba và bảy ngày (nhánh 9) — của <strong>cả kế hoạch</strong>, không phải
     * mỗi ngày một lần.
     *
     * <p>Bảy ngày không được tốn bảy lần hai giây: cấu trúc bữa và danh sách ứng viên dựng đúng
     * một lượt rồi dùng lại cho mọi ngày, nên phần thật sự lặp lại chỉ là vòng beam. Một ngân sách
     * chung cũng là cách duy nhất để người dùng chờ một khoảng đoán được, thay vì chờ theo số ngày
     * họ chọn.</p>
     */
    public static final Duration MULTI_DAY_BUDGET = Duration.ofSeconds(5);

    /** Hạn của một lượt lập {@code dayCount} ngày. */
    public static Duration budgetFor(int dayCount) {
        return dayCount <= 1 ? ONE_DAY_BUDGET : MULTI_DAY_BUDGET;
    }

    private final LongSupplier nanoTime;
    private final long startedAtNanos;
    private final long budgetNanos;

    private SearchDeadline(LongSupplier nanoTime, long budgetNanos) {
        this.nanoTime = nanoTime;
        this.startedAtNanos = nanoTime.getAsLong();
        this.budgetNanos = budgetNanos;
    }

    public static SearchDeadline of(Duration budget) {
        return new SearchDeadline(System::nanoTime, budget.toNanos());
    }

    /** Đồng hồ thay thế cho test — cách duy nhất tái hiện TC19 mà không phải chờ hai giây thật. */
    public static SearchDeadline of(Duration budget, LongSupplier nanoTime) {
        return new SearchDeadline(nanoTime, budget.toNanos());
    }

    /** Hạn đã hết ngay từ đầu: dựng lại TC19 một cách xác định. */
    public static SearchDeadline alreadyExpired() {
        return new SearchDeadline(System::nanoTime, 0L);
    }

    public boolean exceeded() {
        return nanoTime.getAsLong() - startedAtNanos >= budgetNanos;
    }

    /** Thời gian đã trôi qua — ghi lại để đối chiếu với mục tiêu 2 giây. */
    public Duration elapsed() {
        return Duration.ofNanos(nanoTime.getAsLong() - startedAtNanos);
    }
}
