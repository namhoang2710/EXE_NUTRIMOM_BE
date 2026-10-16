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

    /** Hạn cho thực đơn một ngày. Bảy ngày là 5 giây và thuộc nhánh 9. */
    public static final Duration ONE_DAY_BUDGET = Duration.ofSeconds(2);

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
