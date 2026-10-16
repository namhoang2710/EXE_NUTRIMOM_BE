package vn.nutrimom.notification.domain;

/** Business transitions only. Stable names are also the recipient deduplication key prefix. */
public enum AdminNotificationEvent {
    ADMIN_SUBSCRIPTION_CHANGED(NotificationCategory.PAYMENT, NotificationType.SYSTEM,
            "Gói dịch vụ đã thay đổi", "Thông tin gói dịch vụ của khách hàng đã được cập nhật.", "SUBSCRIPTION_CHANGE", "users"),
    ADMIN_ACCOUNT_CREATED(NotificationCategory.ACCOUNT, NotificationType.SYSTEM,
            "Có tài khoản khách hàng mới", "Một khách hàng vừa đăng ký tài khoản.", "USER", "users"),
    ADMIN_ACCOUNT_ACTIVATED(NotificationCategory.ACCOUNT, NotificationType.SYSTEM,
            "Tài khoản đã được kích hoạt", "Một khách hàng đã xác thực và kích hoạt tài khoản.", "USER", "users"),
    ADMIN_ACCOUNT_DISABLED(NotificationCategory.ACCOUNT, NotificationType.SYSTEM,
            "Khách hàng vô hiệu hóa tài khoản", "Một khách hàng đã xác nhận vô hiệu hóa tài khoản.", "USER", "users"),
    ADMIN_PAYMENT_PAID(NotificationCategory.PAYMENT, NotificationType.SYSTEM,
            "Khách hàng mua gói thành công", "Giao dịch mua gói đã được xác nhận.", "PAYMENT_ORDER", "payment-orders"),
    ADMIN_PAYMENT_RENEWED(NotificationCategory.PAYMENT, NotificationType.SYSTEM,
            "Khách hàng gia hạn gói thành công", "Giao dịch gia hạn gói đã được xác nhận.", "PAYMENT_ORDER", "payment-orders"),
    ADMIN_CONTACT_NEW(NotificationCategory.SUPPORT, NotificationType.CONTACT,
            "Có yêu cầu hỗ trợ mới", "Một người dùng vừa gửi yêu cầu hỗ trợ. Mở hộp thư để xem và liên hệ lại.", "CONTACT_REQUEST", "contact-requests"),
    ADMIN_CONTACT_COMPLETED(NotificationCategory.SUPPORT, NotificationType.CONTACT,
            "Yêu cầu hỗ trợ đã hoàn tất", "Đội hỗ trợ đã hoàn tất một yêu cầu của khách hàng.", "CONTACT_REQUEST", "contact-requests"),
    ADMIN_CONTACT_CANCELLED(NotificationCategory.SUPPORT, NotificationType.CONTACT,
            "Khách hàng hủy yêu cầu hỗ trợ", "Một yêu cầu hỗ trợ đã được khách hàng hủy.", "CONTACT_REQUEST", "contact-requests"),
    ADMIN_APPOINTMENT_CREATED(NotificationCategory.APPOINTMENT, NotificationType.CONSULTATION,
            "Có lịch tư vấn mới", "Một khách hàng vừa đặt lịch tư vấn với chuyên gia.", "CONSULTATION", "appointments"),
    ADMIN_EXPERT_WAITING(NotificationCategory.APPOINTMENT, NotificationType.CONSULTATION,
            "Có yêu cầu đang chờ chuyên gia", "Một yêu cầu tư vấn cần chuyên gia tiếp nhận.", "ADMIN_EXPERT_WAITING", "appointments"),
    ADMIN_ACCEPTANCE_OVERDUE(NotificationCategory.APPOINTMENT, NotificationType.CONSULTATION,
            "Yêu cầu quá hạn tiếp nhận", "Yêu cầu tư vấn đã quá hạn tiếp nhận và cần được xử lý.", "CONSULTATION", "appointments"),
    ADMIN_APPOINTMENT_ACCEPTED(NotificationCategory.APPOINTMENT, NotificationType.CONSULTATION,
            "Chuyên gia đã nhận lịch tư vấn", "Một yêu cầu tư vấn đã được chuyên gia tiếp nhận.", "CONSULTATION", "appointments"),
    ADMIN_APPOINTMENT_COMPLETED(NotificationCategory.APPOINTMENT, NotificationType.CONSULTATION,
            "Buổi tư vấn đã hoàn tất", "Một buổi tư vấn đã được chuyên gia xác nhận hoàn tất.", "CONSULTATION", "appointments"),
    ADMIN_APPOINTMENT_CANCELLED(NotificationCategory.APPOINTMENT, NotificationType.CONSULTATION,
            "Lịch tư vấn đã bị hủy", "Một khách hàng đã hủy yêu cầu tư vấn.", "CONSULTATION", "appointments");

    private final NotificationCategory category;
    private final NotificationType type;
    private final String title;
    private final String body;
    private final String sourceType;
    private final String route;

    AdminNotificationEvent(NotificationCategory category, NotificationType type, String title,
            String body, String sourceType, String route) {
        this.category = category;
        this.type = type;
        this.title = title;
        this.body = body;
        this.sourceType = sourceType;
        this.route = route;
    }

    public NotificationCategory category() { return category; }
    public NotificationType type() { return type; }
    public String title() { return title; }
    public String body() { return body; }
    public String sourceType() { return sourceType; }
    public String deepLink(String reference) { return "nutrimom://admin/" + route + "/" + reference; }
}
