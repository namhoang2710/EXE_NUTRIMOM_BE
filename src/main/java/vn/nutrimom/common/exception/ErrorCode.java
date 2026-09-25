package vn.nutrimom.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Canonical catalog of API error codes (spec mục 20 "Error code bắt buộc" + các code domain đang dùng).
 *
 * <p>Đây là nguồn sự thật duy nhất cho error code của backend NutriMom. Mọi nơi phát lỗi
 * (service, {@link GlobalExceptionHandler}, lớp security) phải tham chiếu hằng ở đây thay vì
 * literal string, để FE và các module khác bám theo một hợp đồng ổn định.</p>
 *
 * <p>Mỗi hằng mang sẵn HTTP status mặc định và cờ {@code retryable}. Tên hằng chính là code
 * string trả về trên wire ({@link #code()} = {@link #name()}). Bảng đối chiếu đầy đủ nằm ở
 * {@code docs/error-codes.md}.</p>
 *
 * <p>Các hằng đánh dấu "reserved" là code bắt buộc theo spec nhưng module tương ứng chưa được
 * triển khai (files, experts, scan, subscription, CMS). Định nghĩa sẵn để các nhánh sau import
 * mà không phải tự đặt lại tên.</p>
 */
public enum ErrorCode {

    // --- Spec mục 20: code bắt buộc dùng chung ---
    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_CONTENT, false, "Dữ liệu gửi lên chưa hợp lệ."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, false, "Thông tin xác thực không hợp lệ."),
    FORBIDDEN(HttpStatus.FORBIDDEN, false, "Bạn không có quyền thực hiện thao tác này."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Không tìm thấy tài nguyên."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, false,
            "Dữ liệu đã được cập nhật ở nơi khác. Vui lòng tải lại và thử lại."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, false,
            "Yêu cầu trùng idempotency key với một thao tác khác."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true, "Bạn thao tác quá nhanh. Vui lòng thử lại sau."),
    OTP_EXPIRED(HttpStatus.UNAUTHORIZED, false, "Mã OTP đã hết hạn."),
    INVALID_OTP(HttpStatus.UNAUTHORIZED, false, "Mã OTP không đúng."),
    OTP_ATTEMPTS_EXCEEDED(HttpStatus.UNAUTHORIZED, false, "Bạn đã nhập sai OTP quá số lần cho phép."),
    OTP_CHALLENGE_USED(HttpStatus.UNAUTHORIZED, false, "Phiên OTP này đã được sử dụng."),
    OTP_DEVICE_MISMATCH(HttpStatus.UNAUTHORIZED, false, "Thiết bị không khớp với phiên OTP."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, false, "Refresh token không hợp lệ."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, false, "Tệp vượt quá dung lượng cho phép."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false, "Định dạng tệp không được hỗ trợ."),
    MALWARE_DETECTED(HttpStatus.UNPROCESSABLE_CONTENT, false, "Tệp có dấu hiệu chứa mã độc."),
    UPLOAD_NOT_COMPLETE(HttpStatus.CONFLICT, false, "Tệp chưa hoàn tất tải lên."),
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, false, "Khung giờ đã được đặt. Vui lòng chọn khung khác."),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, false, "Bạn đã dùng hết hạn mức cho phép."),
    SCAN_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, false, "Xử lý ảnh/tài liệu thất bại."),
    SHARING_SCOPE_REQUIRED(HttpStatus.FORBIDDEN, false, "Bạn chưa được cấp quyền xem nội dung này."),
    CONTENT_NOT_REVIEWED(HttpStatus.CONFLICT, false, "Nội dung chưa được duyệt."),

    // --- Auth (đang dùng) ---
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, false, "Số điện thoại đã được đăng ký."),
    INVALID_PHONE(HttpStatus.UNPROCESSABLE_CONTENT, false, "Số điện thoại không hợp lệ."),
    TERMS_NOT_ACCEPTED(HttpStatus.UNPROCESSABLE_CONTENT, false, "Bạn cần đồng ý điều khoản trước khi tiếp tục."),
    OTP_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, true, "Vui lòng chờ trước khi gửi lại OTP."),
    OTP_REQUEST_IN_PROGRESS(HttpStatus.TOO_MANY_REQUESTS, true, "Một yêu cầu OTP đang được xử lý."),
    OTP_PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, true, "Dịch vụ gửi OTP chưa sẵn sàng."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, false, "Thông tin đăng nhập không đúng."),
    OTP_CHALLENGE_NOT_FOUND(HttpStatus.UNAUTHORIZED, false, "Không tìm thấy phiên OTP."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Không tìm thấy tài khoản."),
    ACCOUNT_UNAVAILABLE(HttpStatus.UNAUTHORIZED, false, "Tài khoản không khả dụng."),
    OTP_REAUTHENTICATION_MISMATCH(HttpStatus.UNAUTHORIZED, false, "Phiên xác thực lại không khớp."),

    // --- User (đang dùng) ---
    REAUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, false, "Vui lòng xác thực lại để tiếp tục."),
    INVALID_REAUTHENTICATION(HttpStatus.UNAUTHORIZED, false, "Xác thực lại không hợp lệ."),

    // --- Family (đang dùng) ---
    INVALID_INVITATION_TOKEN(HttpStatus.NOT_FOUND, false, "Mã lời mời không hợp lệ."),
    INVITATION_ALREADY_USED(HttpStatus.CONFLICT, false, "Mã lời mời đã được sử dụng."),
    INVITATION_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, false, "Mã lời mời đã hết hạn."),
    INVITATION_TARGET_MISMATCH(HttpStatus.FORBIDDEN, false, "Mã lời mời thuộc về một tài khoản khác."),
    FAMILY_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Không tìm thấy nhóm gia đình."),
    OWNER_ALREADY_IN_GROUP(HttpStatus.CONFLICT, false, "Chủ thai kỳ không cần tham gia nhóm."),
    FAMILY_MEMBER_EXISTS(HttpStatus.CONFLICT, false, "Tài khoản đã là thành viên của nhóm này."),

    // --- Pregnancy (đang dùng) ---
    PREGNANCY_NOT_ACTIVE(HttpStatus.CONFLICT, false, "Thai kỳ không ở trạng thái hoạt động."),
    ACTIVE_PREGNANCY_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Không tìm thấy thai kỳ đang hoạt động."),
    PREGNANCY_ARCHIVED(HttpStatus.CONFLICT, false, "Thai kỳ đã được lưu trữ."),
    ACTIVE_PREGNANCY_EXISTS(HttpStatus.CONFLICT, false, "Đã tồn tại một thai kỳ đang hoạt động."),

    // --- Knowledge / CMS (đang dùng) ---
    ARTICLE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "Không tìm thấy bài viết."),
    SLUG_ALREADY_EXISTS(HttpStatus.CONFLICT, false, "Slug bài viết đã tồn tại."),
    DB_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, true, "Không lưu được dữ liệu. Vui lòng thử lại."),
    R2_UPLOAD_FAILED(HttpStatus.BAD_GATEWAY, true, "Không tải được ảnh lên kho lưu trữ. Vui lòng thử lại."),
    INVALID_FILE_TYPE(HttpStatus.UNPROCESSABLE_CONTENT, false, "Chỉ hỗ trợ ảnh JPEG, PNG, WebP."),
    INVALID_IMAGE_DIMENSIONS(HttpStatus.UNPROCESSABLE_CONTENT, false, "Kích thước ảnh vượt giới hạn xử lý an toàn."),
    IMAGE_PROCESSING_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, false, "Không xử lý được ảnh. Vui lòng chọn ảnh khác."),
    IMAGE_PROCESSING_BUSY(HttpStatus.TOO_MANY_REQUESTS, true, "Hệ thống xử lý ảnh đang bận. Vui lòng thử lại."),

    // --- Consultation / experts (đặt lịch tư vấn 1-1) ---
    REQUEST_ALREADY_CLAIMED(HttpStatus.CONFLICT, false,
            "Yêu cầu đã được một chuyên gia khác tiếp nhận."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, false, "Bạn đã đánh giá buổi tư vấn này rồi."),
    REVIEW_NOT_ALLOWED(HttpStatus.CONFLICT, false,
            "Chỉ có thể đánh giá sau khi buổi tư vấn đã hoàn thành."),
    INVALID_CONSULTATION_STATE(HttpStatus.CONFLICT, false,
            "Không thể thực hiện thao tác này với trạng thái hiện tại của yêu cầu."),

    // --- Contact (hộp thư hỗ trợ) ---
    INVALID_CONTACT_REQUEST_STATE(HttpStatus.CONFLICT, false,
            "Không thể thực hiện thao tác này với trạng thái hiện tại của yêu cầu hỗ trợ."),
    CONTACT_REQUEST_LIMIT_REACHED(HttpStatus.CONFLICT, false,
            "Bạn đang có quá nhiều yêu cầu hỗ trợ chờ xử lý. Vui lòng chờ phản hồi trước khi gửi thêm."),

    // --- Handler-level (extension, ngoài spec — giữ nguyên hành vi hiện tại) ---
    INVALID_MULTIPART(HttpStatus.BAD_REQUEST, false, "Cần một tệp ảnh multipart hợp lệ."),
    INVALID_CONTENT_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false, "Content-Type của yêu cầu không được hỗ trợ."),
    INVALID_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, false, "Tham số yêu cầu không hợp lệ."),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, false, "Nội dung JSON không đúng định dạng."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, true, "Hệ thống đang gặp sự cố. Vui lòng thử lại sau.");

    private final HttpStatus status;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, boolean retryable, String defaultMessage) {
        this.status = status;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    /** Code string trả về cho client (bằng đúng tên hằng). */
    public String code() {
        return name();
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
