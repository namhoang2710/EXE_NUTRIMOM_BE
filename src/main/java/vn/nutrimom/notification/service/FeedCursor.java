package vn.nutrimom.notification.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Con trỏ keyset cho {@code GET /notifications} và {@code GET /activity-feed}.
 *
 * <p>Danh sách sắp theo {@code (created_at desc, id desc)}, nên một trang được xác định bằng cặp
 * (mốc thời gian, id) của dòng cuối trang trước. Khác cursor kiểu offset, cặp này không bị lệch khi
 * có bản ghi mới chèn vào đầu danh sách giữa hai lần gọi — yêu cầu "Cursor ổn định khi có insert
 * mới" của spec mục 22.</p>
 *
 * <p>Mốc thời gian được mã hoá nguyên vẹn dạng ISO-8601 (không rút về epoch milli) để không mất độ
 * chính xác 100ns của {@code DATETIMEOFFSET(7)}; mất phần lẻ sẽ làm lọt hoặc lặp dòng.</p>
 */
public record FeedCursor(OffsetDateTime at, String id) {

    private static final String SEPARATOR = "|";

    /**
     * Mốc bắt đầu cho trang đầu tiên: lớn hơn mọi {@code created_at} có thật, nên điều kiện
     * {@code created_at < :cursorAt} luôn đúng và trang đầu dùng chung một câu truy vấn với các
     * trang sau. Nhánh so sánh id không bao giờ chạy với mốc này vì không dòng nào trùng thời điểm.
     */
    private static final OffsetDateTime SENTINEL_AT =
            OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
    private static final String SENTINEL_ID = "";

    /** Cursor rỗng/null từ client nghĩa là lấy trang đầu. */
    public static FeedCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new FeedCursor(SENTINEL_AT, SENTINEL_ID);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()),
                    StandardCharsets.UTF_8);
            int separator = decoded.indexOf(SEPARATOR);
            if (separator < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new FeedCursor(
                    OffsetDateTime.parse(decoded.substring(0, separator)),
                    decoded.substring(separator + 1));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Tham số cursor không hợp lệ.");
        }
    }

    /** Chuỗi opaque trả cho client qua {@code next_cursor}. */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (at.toString() + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }
}
