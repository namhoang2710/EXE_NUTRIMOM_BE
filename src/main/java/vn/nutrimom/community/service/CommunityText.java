package vn.nutrimom.community.service;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

/**
 * Chuẩn hoá văn bản người dùng nhập (bài viết, bình luận, ghi chú báo cáo).
 *
 * <p>Nội dung lưu và trả về dạng văn bản thuần; FE hiển thị bằng text node nên thẻ HTML không bao giờ
 * được thực thi. Ở đây chỉ bỏ ký tự điều khiển/vô hình dễ dùng để giả mạo, gộp dòng trống thừa và
 * đếm độ dài theo code point (emoji tính là 1).</p>
 */
public final class CommunityText {
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private CommunityText() {
    }

    /** Bắt buộc có nội dung, tối đa {@code max} ký tự. */
    public static String required(String raw, int max, String field) {
        String value = clean(raw);
        if (value == null || value.isEmpty()) {
            throw invalid(field, field + " không được để trống");
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw invalid(field, field + " tối đa " + max + " ký tự");
        }
        return value;
    }

    /** Không bắt buộc: trả null khi rỗng. */
    public static String optional(String raw, int max, String field) {
        String value = clean(raw);
        if (value == null || value.isEmpty()) return null;
        if (value.codePointCount(0, value.length()) > max) {
            throw invalid(field, field + " tối đa " + max + " ký tự");
        }
        return value;
    }

    static String clean(String raw) {
        if (raw == null) return null;
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
        value = INVISIBLE.matcher(value).replaceAll("");
        return EXTRA_BLANK_LINES.matcher(value).replaceAll("\n\n").strip();
    }

    /** Đoạn trích ngắn cho danh sách đã lưu / admin. */
    public static String excerpt(String content, int max) {
        if (content == null) return null;
        String flat = content.replace('\n', ' ');
        if (flat.codePointCount(0, flat.length()) <= max) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, max)).stripTrailing() + "…";
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, Map.of(field, message));
    }
}
