package vn.nutrimom.community.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Cursor keyset (mốc thời gian, id) — cùng định dạng base64 với {@code notification.service.FeedCursor}.
 *
 * <p>Không có cursor = trang đầu: dùng mốc canh biên (lớn nhất cho danh sách giảm dần, nhỏ nhất cho
 * danh sách tăng dần) để trang đầu và trang sau dùng chung một câu truy vấn.</p>
 */
public record CommunityCursor(OffsetDateTime at, String id) {
    private static final String SEPARATOR = "|";
    private static final OffsetDateTime MAX = OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
    private static final OffsetDateTime MIN = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    public static CommunityCursor descending(String cursor) {
        return blank(cursor) ? new CommunityCursor(MAX, "") : decode(cursor);
    }

    public static CommunityCursor ascending(String cursor) {
        return blank(cursor) ? new CommunityCursor(MIN, "") : decode(cursor);
    }

    public static boolean blank(String cursor) {
        return cursor == null || cursor.isBlank();
    }

    /** Một trang đã cắt: các dòng trả về, cursor của trang sau (null nếu hết) và cờ còn trang. */
    public record Window<T>(List<T> items, String nextCursor, boolean hasMore) { }

    /**
     * Cắt kết quả truy vấn {@code limit + 1} dòng thành một trang. Dòng dư chỉ dùng để biết còn trang
     * sau mà không phải COUNT; cursor lấy từ dòng cuối của trang.
     */
    public static <T> Window<T> window(List<T> rows, int limit, Function<T, OffsetDateTime> at,
            Function<T, String> id) {
        if (rows.size() <= limit) return new Window<>(rows, null, false);
        List<T> page = rows.subList(0, limit);
        T last = page.get(limit - 1);
        return new Window<>(page, encode(at.apply(last), id.apply(last)), true);
    }

    public static String encode(OffsetDateTime at, String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((at.toString() + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    private static CommunityCursor decode(String cursor) {
        try {
            if (cursor.length() > 200) throw new IllegalArgumentException("too long");
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(SEPARATOR);
            if (separator < 0) throw new IllegalArgumentException("missing separator");
            return new CommunityCursor(OffsetDateTime.parse(decoded.substring(0, separator)),
                    decoded.substring(separator + 1));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Tham số cursor không hợp lệ.");
        }
    }
}
