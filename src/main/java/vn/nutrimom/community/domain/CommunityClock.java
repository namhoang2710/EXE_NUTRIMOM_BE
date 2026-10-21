package vn.nutrimom.community.domain;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Mốc thời gian cắt về micro giây, đúng độ chính xác của TIMESTAMPTZ.
 *
 * <p>Cursor keyset so sánh bằng {@code =} trên mốc thời gian. Nếu Java giữ nano giây mà DB chỉ lưu
 * micro giây, giá trị trong cursor sẽ không bao giờ bằng giá trị đọc lại → lọt hoặc lặp dòng.</p>
 */
public final class CommunityClock {
    private CommunityClock() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
