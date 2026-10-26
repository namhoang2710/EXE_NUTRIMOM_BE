package vn.nutrimom.common.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final Optional<JavaMailSender> mailSender;
    private final String fromEmail;
    private final String resendApiKey;
    private final String resendFrom;
    private final String brevoApiKey;
    private final String brevoSenderEmail;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructor tương thích ngược cho unit test (không chứa annotation Spring).
     */
    public EmailService(JavaMailSender mailSender,
                        String fromEmail,
                        String mailUsername) {
        this(mailSender, fromEmail, mailUsername, "", "NutriMom <onboarding@resend.dev>", "", "namhoang20032710@gmail.com", new ObjectMapper());
    }

    /**
     * Constructor tương thích ngược cho unit test Resend (không chứa annotation Spring).
     */
    public EmailService(JavaMailSender mailSender,
                        String fromEmail,
                        String mailUsername,
                        String resendApiKey,
                        String resendFrom,
                        ObjectMapper objectMapper) {
        this(mailSender, fromEmail, mailUsername, resendApiKey, resendFrom, "", "namhoang20032710@gmail.com", objectMapper);
    }

    /**
     * Hỗ trợ Brevo API và Resend API qua HTTP (cổng 443) khi có API key.
     */
    @Autowired
    public EmailService(@Autowired(required = false) JavaMailSender mailSender,
                        @Value("${app.mail.from:}") String fromEmail,
                        @Value("${spring.mail.username:}") String mailUsername,
                        @Value("${resend.api-key:${RESEND_API_KEY:}}") String resendApiKey,
                        @Value("${resend.from:${RESEND_FROM:NutriMom <onboarding@resend.dev>}}") String resendFrom,
                        @Value("${brevo.api-key:${BREVO_API_KEY:}}") String brevoApiKey,
                        @Value("${brevo.sender-email:${BREVO_SENDER_EMAIL:namhoang20032710@gmail.com}}") String brevoSenderEmail,
                        @Autowired(required = false) ObjectMapper objectMapper) {
        this.mailSender = Optional.ofNullable(mailSender);
        String resolvedResendKey = resendApiKey != null && !resendApiKey.isBlank()
                ? resendApiKey.trim()
                : Optional.ofNullable(System.getenv("RESEND_API_KEY")).orElse("").trim();
        this.resendApiKey = resolvedResendKey;
        this.resendFrom = resendFrom != null && !resendFrom.isBlank() ? resendFrom.trim() : "NutriMom <onboarding@resend.dev>";

        String resolvedBrevoKey = brevoApiKey != null && !brevoApiKey.isBlank()
                ? brevoApiKey.trim()
                : Optional.ofNullable(System.getenv("BREVO_API_KEY")).orElse("").trim();
        this.brevoApiKey = resolvedBrevoKey;
        this.brevoSenderEmail = brevoSenderEmail != null && !brevoSenderEmail.isBlank()
                ? brevoSenderEmail.trim()
                : Optional.ofNullable(System.getenv("BREVO_SENDER_EMAIL")).orElse("namhoang20032710@gmail.com").trim();

        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (fromEmail != null && !fromEmail.isBlank()) {
            this.fromEmail = fromEmail;
        } else if (mailUsername != null && !mailUsername.isBlank()) {
            this.fromEmail = mailUsername;
        } else {
            this.fromEmail = "";
        }

        if (!this.brevoApiKey.isBlank()) {
            log.info("EmailService đã kích hoạt Brevo HTTP API với sender: {}", this.brevoSenderEmail);
        } else if (!this.resendApiKey.isBlank()) {
            log.info("EmailService đã kích hoạt Resend HTTP API với sender: {}", resolvedSender());
        } else {
            log.info("EmailService chạy chế độ SMTP/Mock (không có BREVO_API_KEY hoặc RESEND_API_KEY)");
        }
    }

    /**
     * Địa chỉ gửi đã phân giải, hoặc chuỗi rỗng nếu không có nguồn nào.
     *
     * <p>Package-private để test khẳng định được thứ tự ưu tiên mà không phải dựng SMTP — cùng lý
     * do {@link #familyInvitationHtml} được mở ra ở mức này.</p>
     */
    String resolvedSender() {
        if (!fromEmail.isBlank()) {
            return fromEmail;
        }
        if (!brevoApiKey.isBlank() && !brevoSenderEmail.isBlank()) {
            return brevoSenderEmail;
        }
        if (!resendApiKey.isBlank()) {
            return resendFrom;
        }
        return "";
    }

    /**
     * Kết quả một lần gửi.
     *
     * <p>Thay cho {@code boolean} vì "hỏng" mà không nói hỏng vì sao thì người trực vận hành
     * không làm gì được: cấu hình thiếu, sai mật khẩu và SMTP từ chối đều hiện ra giống hệt
     * nhau.</p>
     *
     * @param errorClass {@code null} khi gửi được. Ngoài ra là tên lớp ngoại lệ, hoặc một trong
     *                   các mã tự đặt ({@link #NOT_CONFIGURED}, {@link #NO_SENDER}) cho những ca
     *                   hỏng không sinh ngoại lệ nào. <strong>Không bao giờ</strong> là
     *                   {@code getMessage()} — message của SMTP server hay chép lại nguyên địa
     *                   chỉ người nhận.
     */
    public record MailResult(boolean sent, String errorClass) {

        /** Không có bean {@code JavaMailSender}: môi trường chưa khai {@code SPRING_MAIL_HOST}. */
        public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

        /** Không biết gửi từ địa chỉ nào: thiếu cả {@code APP_MAIL_FROM} lẫn SMTP username. */
        public static final String NO_SENDER = "NO_SENDER";

        /** Tên {@code ok} chứ không phải {@code sent}: {@code sent()} đã là accessor của record. */
        public static MailResult ok() {
            return new MailResult(true, null);
        }

        public static MailResult failed(String errorClass) {
            return new MailResult(false, errorClass);
        }

        public static MailResult failed(Exception cause) {
            return failed(cause.getClass().getSimpleName());
        }
    }

    /**
     * Gửi một email HTML. {@code sent() == false} nghĩa là thư KHÔNG ra khỏi tiến trình này.
     *
     * <p><strong>Nội dung thư không bao giờ được log.</strong> Thân thư mang bí mật dùng được
     * ngay: link mời nhúng raw token, email OTP nhúng mã 6 số. In chúng ra log là biến mọi người
     * đọc được log thành người chiếm được tài khoản — kể cả khi đường truyền SMTP vẫn an toàn.
     * Địa chỉ người nhận cũng chỉ xuất hiện dưới dạng đã che, và ngoại lệ chỉ hiện ra dưới dạng
     * tên lớp.</p>
     */
    public MailResult sendHtml(String to, String subject, String htmlContent) {
        if (!brevoApiKey.isBlank()) {
            return sendViaBrevo(to, subject, htmlContent);
        }
        if (!resendApiKey.isBlank()) {
            return sendViaResend(to, subject, htmlContent);
        }

        if (mailSender.isEmpty()) {
            log.info("[MOCK EMAIL] MailSender chưa được cấu hình, bỏ qua email tới {}.", mask(to));
            return MailResult.failed(MailResult.NOT_CONFIGURED);
        }
        if (fromEmail.isBlank()) {
            // Gửi với sender rỗng thì SMTP server từ chối hoặc tự thay bằng địa chỉ khác; cả hai
            // đều khó lần ra hơn là nói thẳng ở đây.
            log.warn("Chưa cấu hình địa chỉ gửi (APP_MAIL_FROM hoặc SPRING_MAIL_USERNAME), "
                    + "bỏ qua email tới {}.", mask(to));
            return MailResult.failed(MailResult.NO_SENDER);
        }

        try {
            JavaMailSender sender = mailSender.get();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "NutriMom");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            sender.send(message);
            log.info("Đã gửi email thành công tới {}.", mask(to));
            return MailResult.ok();
        } catch (Exception e) {
            // Chỉ lớp ngoại lệ: message của SMTP server thường chép lại nguyên địa chỉ người nhận,
            // và stack trace đầy đủ thì kéo theo cả header lẫn thân thư.
            log.warn("Không gửi được email tới {}: {}. Vui lòng kiểm tra cấu hình SMTP.",
                    mask(to), e.getClass().getSimpleName());
            return MailResult.failed(e);
        }
    }

    private MailResult sendViaBrevo(String to, String subject, String htmlContent) {
        try {
            String senderEmail = brevoSenderEmail != null && !brevoSenderEmail.isBlank()
                    ? brevoSenderEmail
                    : "namhoang20032710@gmail.com";

            Map<String, Object> payload = Map.of(
                    "sender", Map.of("name", "NutriMom", "email", senderEmail),
                    "to", List.of(Map.of("email", to)),
                    "subject", singleLine(subject),
                    "htmlContent", htmlContent
            );
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .header("api-key", brevoApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "NutriMom-Backend/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                log.info("Đã gửi email thành công qua Brevo API tới {}.", mask(to));
                return MailResult.ok();
            } else {
                log.warn("Lỗi gửi email qua Brevo API tới {}: HTTP {} - {}", mask(to), code, response.body());
                return MailResult.failed("BrevoHttp_" + code);
            }
        } catch (Exception e) {
            log.warn("Không gửi được email qua Brevo API tới {}: {}", mask(to), e.getClass().getSimpleName());
            return MailResult.failed(e);
        }
    }

    private MailResult sendViaResend(String to, String subject, String htmlContent) {
        try {
            String sender;
            if (resendFrom != null && !resendFrom.isBlank() && !resendFrom.equals("NutriMom <onboarding@resend.dev>")) {
                sender = resendFrom;
            } else if (!fromEmail.isBlank() && !fromEmail.contains("@gmail.com") && !fromEmail.contains("@fpt.edu.vn")) {
                sender = fromEmail;
            } else {
                sender = "NutriMom <onboarding@resend.dev>";
            }

            Map<String, Object> payload = Map.of(
                    "from", sender,
                    "to", List.of(to),
                    "subject", singleLine(subject),
                    "html", htmlContent
            );
            String json = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + resendApiKey)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NutriMom-Backend/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                log.info("Đã gửi email thành công qua Resend API tới {}.", mask(to));
                return MailResult.ok();
            } else {
                log.warn("Lỗi gửi email qua Resend API tới {}: HTTP {} - {}", mask(to), code, response.body());
                return MailResult.failed("ResendHttp_" + code);
            }
        } catch (Exception e) {
            log.warn("Không gửi được email qua Resend API tới {}: {}", mask(to), e.getClass().getSimpleName());
            return MailResult.failed(e);
        }
    }

    public MailResult sendEmailOtp(String toEmail, String code, String directLink) {
        String subject = "🔐 Mã xác thực đăng nhập NutriMom: " + code;
        String html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Mã xác thực NutriMom</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 560px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #7c3aed 0%%, #a855f7 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 6px 0 0; opacity: 0.9; font-size: 14px; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .code-box { text-align: center; margin: 28px 0; }
                .code { display: inline-block; font-size: 34px; font-weight: 800; letter-spacing: 10px; color: #7c3aed; background: #f5f3ff; border: 2px dashed #c4b5fd; padding: 14px 32px; border-radius: 12px; font-family: 'Courier New', monospace; }
                .note { background: #f1f5f9; padding: 14px 16px; border-radius: 8px; font-size: 13px; color: #64748b; margin-top: 24px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
                .btn-link { display: inline-block; margin-top: 14px; color: #7c3aed; font-size: 14px; font-weight: 600; text-decoration: underline; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header">
                  <h1>NutriMom</h1>
                  <p>Đồng hành cùng hành trình thai kỳ trọn vẹn</p>
                </div>
                <div class="content">
                  <p>Xin chào,</p>
                  <p>Chúng tôi nhận được yêu cầu đăng nhập vào tài khoản NutriMom với email <strong>%s</strong>.</p>
                  <p>Mã xác thực 6 chữ số của bạn là:</p>
                  <div class="code-box">
                    <span class="code">%s</span>
                  </div>
                  <p style="text-align: center; font-size: 14px; color: #475569;">
                    Hãy nhập mã xác thực này vào trang web để đăng nhập vào tài khoản của bạn.
                  </p>
                  <div class="note">
                    ⏱️ <strong>Lưu ý:</strong> Mã này có hiệu lực trong vòng <strong>10 phút</strong> và chỉ sử dụng được <strong>1 lần duy nhất</strong>.<br>
                    Nếu bạn không yêu cầu đăng nhập, vui lòng bỏ qua email này để bảo vệ tài khoản.
                  </div>
                  %s
                </div>
                <div class="footer">
                  © 2026 NutriMom Health & Nutrition. Mọi quyền được bảo lưu.
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                toEmail,
                code,
                directLink != null ? """
                  <div style="text-align: center; margin-top: 20px;">
                    <a href="%s" class="btn-link" target="_blank">👉 Hoặc bấm vào đây để đăng nhập trực tiếp</a>
                  </div>
                """.formatted(directLink) : ""
            );

        return sendHtml(toEmail, subject, html);
    }

    public MailResult sendMagicLink(String toEmail, String magicLinkUrl) {
        String code = "123456";
        if (magicLinkUrl != null && magicLinkUrl.contains("token=")) {
            String tokenPart = magicLinkUrl.substring(magicLinkUrl.indexOf("token=") + 6);
            if (tokenPart.contains("&")) tokenPart = tokenPart.substring(0, tokenPart.indexOf('&'));
            if (tokenPart.length() == 6 && tokenPart.matches("\\d+")) {
                code = tokenPart;
            }
        }
        return sendEmailOtp(toEmail, code, magicLinkUrl);
    }

    public MailResult sendRegistrationConfirmation(String toEmail, String displayName, String code) {
        String subject = "✨ Mã xác thực kích hoạt tài khoản NutriMom: " + code;
        String html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Kích hoạt tài khoản NutriMom</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 560px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #0d9488 0%%, #14b8a6 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 6px 0 0; opacity: 0.9; font-size: 14px; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .code-box { text-align: center; margin: 28px 0; }
                .code { display: inline-block; font-size: 34px; font-weight: 800; letter-spacing: 10px; color: #0d9488; background: #f0fdf4; border: 2px dashed #86efac; padding: 14px 32px; border-radius: 12px; font-family: 'Courier New', monospace; }
                .note { background: #f0fdf4; padding: 14px 16px; border-radius: 8px; font-size: 13px; color: #166534; border: 1px solid #bbf7d0; margin-top: 24px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header">
                  <h1>NutriMom</h1>
                  <p>Đồng hành cùng hành trình thai kỳ trọn vẹn</p>
                </div>
                <div class="content">
                  <p>Xin chào <strong>%s</strong>,</p>
                  <p>Cảm ơn bạn đã đăng ký tài khoản tại <strong>NutriMom</strong>. Mã xác thực 6 chữ số để kích hoạt tài khoản của bạn là:</p>
                  <div class="code-box">
                    <span class="code">%s</span>
                  </div>
                  <p style="text-align: center; font-size: 14px; color: #475569;">
                    Hãy nhập mã xác thực này vào trang web để hoàn tất kích hoạt tài khoản và đăng nhập.
                  </p>
                  <div class="note">
                    ⏱️ <strong>Lưu ý:</strong> Mã có hiệu lực trong vòng <strong>15 phút</strong>. Sau khi kích hoạt, bạn có thể đăng nhập bằng Email hoặc Số điện thoại cùng mật khẩu đã tạo.
                  </div>
                </div>
                <div class="footer">
                  © 2026 NutriMom Health & Nutrition. Mọi quyền được bảo lưu.
                </div>
              </div>
            </body>
            </html>
            """.formatted(escapeHtml(displayName != null ? displayName : "Bạn"), code);

        return sendHtml(toEmail, subject, html);
    }

    public MailResult sendConsultationBookingConfirmationToPatient(
            String toEmail,
            String patientName,
            String doctorName,
            String specialtyLabel,
            String timeLabel,
            String note,
            String consultationLink) {
        String safePatientName = escapeHtml(patientName != null && !patientName.isBlank() ? patientName : "Quý khách");
        String safeDoctorName = escapeHtml(doctorName != null && !doctorName.isBlank() ? doctorName : "Bác sĩ / Chuyên gia NutriMom");
        String safeSpecialty = escapeHtml(specialtyLabel != null && !specialtyLabel.isBlank() ? specialtyLabel : "Tư vấn dinh dưỡng & sức khỏe");
        String safeTime = escapeHtml(timeLabel != null ? timeLabel : "Đang cập nhật");
        String safeNote = escapeHtml(note != null && !note.isBlank() ? note : "Không có ghi chú thêm");
        String safeLink = escapeHtml(consultationLink != null ? consultationLink : "#");

        String subject = "📅 Xác nhận đặt lịch tư vấn thành công - NutriMom";
        String html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Xác nhận đặt lịch tư vấn</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 580px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #0d9488 0%%, #14b8a6 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 6px 0 0; opacity: 0.95; font-size: 14px; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .card { background: #f0fdfa; border: 1px solid #ccfbf1; border-radius: 12px; padding: 20px 22px; margin: 24px 0; }
                .card-title { font-size: 16px; font-weight: 700; color: #0f766e; margin-bottom: 12px; border-bottom: 1px dashed #99f6e4; padding-bottom: 8px; }
                .cta { text-align: center; margin: 28px 0; }
                .btn { display: inline-block; background: #0d9488; color: #ffffff !important; text-decoration: none; font-weight: 700; font-size: 15px; padding: 13px 32px; border-radius: 10px; box-shadow: 0 3px 12px rgba(13,148,136,0.25); }
                .note { background: #f8fafc; border-left: 4px solid #0d9488; padding: 12px 16px; border-radius: 4px; font-size: 13px; color: #475569; margin-top: 24px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header">
                  <h1>NutriMom</h1>
                  <p>Xác nhận lịch hẹn tư vấn trực tuyến</p>
                </div>
                <div class="content">
                  <p>Xin chào <strong>%s</strong>,</p>
                  <p>Bạn đã đặt lịch tư vấn thành công trên hệ thống <strong>NutriMom</strong>. Dưới đây là thông tin chi tiết lịch hẹn của bạn:</p>
                  
                  <div class="card">
                    <div class="card-title">📋 Thông tin buổi tư vấn</div>
                    <table style="width: 100%%; border-collapse: collapse; font-size: 14px;">
                      <tr>
                        <td style="padding: 6px 0; color: #64748b; width: 150px;">⏰ Thời gian hẹn:</td>
                        <td style="padding: 6px 0; color: #0f172a; font-weight: 700;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b;">🩺 Bác sĩ / Chuyên gia:</td>
                        <td style="padding: 6px 0; color: #0f766e; font-weight: 700;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b;">🏷️ Chuyên khoa:</td>
                        <td style="padding: 6px 0; color: #0f172a; font-weight: 600;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b;">💻 Hình thức:</td>
                        <td style="padding: 6px 0; color: #0f172a; font-weight: 600;">Tư vấn trực tuyến qua Video Call NutriMom</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b; vertical-align: top;">📝 Ghi chú tư vấn:</td>
                        <td style="padding: 6px 0; color: #334155;">%s</td>
                      </tr>
                    </table>
                  </div>

                  <div class="cta">
                    <a href="%s" class="btn" target="_blank">Xem chi tiết &amp; Vào phòng tư vấn</a>
                  </div>

                  <div class="note">
                    ⏱️ <strong>Lưu ý:</strong> Vui lòng có mặt trước giờ hẹn <strong>5 phút</strong>, kiểm tra tai nghe, micro và đường truyền Internet ổn định để buổi tư vấn đạt hiệu quả tốt nhất.
                  </div>
                </div>
                <div class="footer">
                  © 2026 NutriMom Health &amp; Nutrition. Mọi quyền được bảo lưu.
                </div>
              </div>
            </body>
            </html>
            """.formatted(safePatientName, safeTime, safeDoctorName, safeSpecialty, safeNote, safeLink);

        return sendHtml(toEmail, subject, html);
    }

    public MailResult sendConsultationBookingNoticeToDoctor(
            String toEmail,
            String doctorName,
            String patientName,
            String specialtyLabel,
            String timeLabel,
            String note,
            String consoleLink) {
        String safeDoctorName = escapeHtml(doctorName != null && !doctorName.isBlank() ? doctorName : "Bác sĩ / Chuyên gia");
        String safePatientName = escapeHtml(patientName != null && !patientName.isBlank() ? patientName : "Bệnh nhân");
        String safeSpecialty = escapeHtml(specialtyLabel != null && !specialtyLabel.isBlank() ? specialtyLabel : "Tư vấn dinh dưỡng & sức khỏe");
        String safeTime = escapeHtml(timeLabel != null ? timeLabel : "Đang cập nhật");
        String safeNote = escapeHtml(note != null && !note.isBlank() ? note : "Không có ghi chú thêm");
        String safeLink = escapeHtml(consoleLink != null ? consoleLink : "#");

        String subject = "🩺 Thông báo lịch hẹn tư vấn mới - NutriMom";
        String html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Thông báo lịch hẹn tư vấn mới</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 580px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #6755a5 0%%, #7a68b8 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 6px 0 0; opacity: 0.95; font-size: 14px; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .card { background: #f5f3ff; border: 1px solid #ddd6fe; border-radius: 12px; padding: 20px 22px; margin: 24px 0; }
                .card-title { font-size: 16px; font-weight: 700; color: #5b21b6; margin-bottom: 12px; border-bottom: 1px dashed #c4b5fd; padding-bottom: 8px; }
                .cta { text-align: center; margin: 28px 0; }
                .btn { display: inline-block; background: #6755a5; color: #ffffff !important; text-decoration: none; font-weight: 700; font-size: 15px; padding: 13px 32px; border-radius: 10px; box-shadow: 0 3px 12px rgba(103,85,165,0.25); }
                .note { background: #f8fafc; border-left: 4px solid #6755a5; padding: 12px 16px; border-radius: 4px; font-size: 13px; color: #475569; margin-top: 24px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header">
                  <h1>NutriMom</h1>
                  <p>Không gian Chuyên gia &amp; Bác sĩ</p>
                </div>
                <div class="content">
                  <p>Kính chào <strong>%s</strong>,</p>
                  <p>Hệ thống <strong>NutriMom</strong> vừa ghi nhận một lịch hẹn tư vấn mới với thông tin chi tiết như sau:</p>
                  
                  <div class="card">
                    <div class="card-title">📋 Chi tiết lịch hẹn từ bệnh nhân</div>
                    <table style="width: 100%%; border-collapse: collapse; font-size: 14px;">
                      <tr>
                        <td style="padding: 6px 0; color: #64748b; width: 150px;">⏰ Thời gian hẹn:</td>
                        <td style="padding: 6px 0; color: #0f172a; font-weight: 700;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b;">👤 Bệnh nhân:</td>
                        <td style="padding: 6px 0; color: #5b21b6; font-weight: 700;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b;">🏷️ Chuyên khoa:</td>
                        <td style="padding: 6px 0; color: #0f172a; font-weight: 600;">%s</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b;">💻 Hình thức:</td>
                        <td style="padding: 6px 0; color: #0f172a; font-weight: 600;">Tư vấn trực tuyến qua Video Call NutriMom</td>
                      </tr>
                      <tr>
                        <td style="padding: 6px 0; color: #64748b; vertical-align: top;">📝 Ghi chú từ bệnh nhân:</td>
                        <td style="padding: 6px 0; color: #334155;">%s</td>
                      </tr>
                    </table>
                  </div>

                  <div class="cta">
                    <a href="%s" class="btn" target="_blank">Mở không gian Chuyên gia để xem lịch</a>
                  </div>

                  <div class="note">
                    ⏱️ <strong>Lưu ý:</strong> Vui lòng xem trước ghi chú của bệnh nhân và vào phòng tư vấn trước <strong>5 phút</strong> để buổi làm việc diễn ra thuận lợi.
                  </div>
                </div>
                <div class="footer">
                  © 2026 NutriMom Health &amp; Nutrition. Mọi quyền được bảo lưu.
                </div>
              </div>
            </body>
            </html>
            """.formatted(safeDoctorName, safeTime, safePatientName, safeSpecialty, safeNote, safeLink);

        return sendHtml(toEmail, subject, html);
    }

    /**
     * Link đặt lại mật khẩu. Nút là thẻ {@code <a>} (Outlook bỏ qua {@code <button>}), link dạng chữ
     * bên dưới là lối thoát cho client chặn thẻ {@code a}. Không đưa token vào tiêu đề thư: tiêu đề
     * hiện trên màn hình khoá và trong danh sách thư.
     */
    public MailResult sendPasswordReset(String toEmail, String displayName, String resetUrl, long ttlMinutes) {
        String subject = "🔑 Đặt lại mật khẩu NutriMom";
        String safeUrl = escapeHtml(resetUrl);
        String html = PASSWORD_MAIL_TEMPLATE.formatted(
                "Đặt lại mật khẩu",
                escapeHtml(displayName != null ? displayName : "Bạn"),
                "Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản NutriMom của bạn. Bấm nút dưới đây để tạo mật khẩu mới:",
                """
                <div class="cta"><a href="%s">Đặt mật khẩu mới</a></div>
                <p class="link">Nếu nút không bấm được, hãy mở liên kết sau: <br><a href="%s">%s</a></p>
                """.formatted(safeUrl, safeUrl, safeUrl),
                "⏱️ Liên kết chỉ dùng được <strong>một lần</strong> và hết hạn sau <strong>%d phút</strong>. Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này, mật khẩu hiện tại vẫn giữ nguyên."
                        .formatted(ttlMinutes));
        return sendHtml(toEmail, subject, html);
    }

    /** Báo mật khẩu vừa đổi, để chủ tài khoản phát hiện kịp nếu không phải mình đổi. */
    public MailResult sendPasswordChanged(String toEmail, String displayName) {
        String html = PASSWORD_MAIL_TEMPLATE.formatted(
                "Mật khẩu đã được thay đổi",
                escapeHtml(displayName != null ? displayName : "Bạn"),
                "Mật khẩu tài khoản NutriMom của bạn vừa được thay đổi. Vì an toàn, mọi thiết bị đang đăng nhập đã được đăng xuất.",
                "",
                "⚠️ Nếu <strong>không phải bạn</strong> thực hiện, hãy dùng chức năng Quên mật khẩu để đặt lại ngay và liên hệ đội hỗ trợ NutriMom.");
        return sendHtml(toEmail, "🔒 Mật khẩu NutriMom của bạn đã được thay đổi", html);
    }

    /** Khung chung cho hai thư mật khẩu: tiêu đề, tên, đoạn mở đầu, khối hành động, ghi chú. */
    private static final String PASSWORD_MAIL_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>%s</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 560px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #0d9488 0%%, #14b8a6 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .cta { text-align: center; margin: 28px 0; }
                .cta a { display: inline-block; background: #0d9488; color: #ffffff; text-decoration: none; font-weight: 700; padding: 14px 32px; border-radius: 10px; }
                .link { font-size: 12px; color: #64748b; word-break: break-all; }
                .note { background: #f0fdf4; padding: 14px 16px; border-radius: 8px; font-size: 13px; color: #166534; border: 1px solid #bbf7d0; margin-top: 24px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header"><h1>NutriMom</h1></div>
                <div class="content">
                  <p>Xin chào <strong>%s</strong>,</p>
                  <p>%s</p>
                  %s
                  <div class="note">%s</div>
                </div>
                <div class="footer">© 2026 NutriMom Health &amp; Nutrition. Mọi quyền được bảo lưu.</div>
              </div>
            </body>
            </html>
            """;

    /**
     * Lời mời tham gia nhóm gia đình, kiểu email mời cộng tác của GitHub.
     *
     * <p>Nút bấm là thẻ {@code <a>} có nền và padding chứ không phải {@code <button>}: Outlook bỏ
     * qua {@code <button>}, và một lời mời không bấm được thì bằng không. Link dạng text phía dưới
     * là lối thoát cho client chặn hẳn thẻ {@code a}.</p>
     *
     * <p>Mọi giá trị do người dùng nhập đều đi qua {@link #escapeHtml}: {@code inviterName} là tên
     * hiển thị người dùng tự đặt, nối thẳng vào HTML là mở đường cho injection.</p>
     *
     * @param acceptUrl luôn là http(s) trỏ về web; KHÔNG dùng scheme riêng kiểu nutrimom://
     */
    public MailResult sendFamilyInvitation(String toEmail, String inviterName,
                                        String relationshipLabel, List<String> scopeLabels,
                                        String acceptUrl, OffsetDateTime expiresAt) {
        // Tiêu đề là header MIME, không phải HTML: ở đây thứ nguy hiểm là ký tự xuống dòng
        // (header injection), không phải dấu ngoặc nhọn.
        String subject = singleLine(inviterName == null ? "Một thành viên" : inviterName)
                + " mời bạn đồng hành cùng thai kỳ trên NutriMom";
        return sendHtml(toEmail, subject, familyInvitationHtml(
                inviterName, relationshipLabel, scopeLabels, acceptUrl, expiresAt));
    }

    /**
     * Dựng HTML của email mời.
     *
     * <p>Tách khỏi {@link #sendFamilyInvitation} để test khẳng định trên chuỗi thật thay vì mổ bụng
     * {@code MimeMessage} — và vì đây mới là chỗ dễ hỏng: template là text block cộng
     * {@code String.formatted}, một dấu {@code %} quên nhân đôi là lỗi lúc chạy.</p>
     */
    String familyInvitationHtml(String inviterName, String relationshipLabel,
                                List<String> scopeLabels, String acceptUrl,
                                OffsetDateTime expiresAt) {
        String safeInviter = escapeHtml(inviterName == null ? "Một thành viên" : inviterName);
        String safeRelationship = escapeHtml(relationshipLabel == null ? "" : relationshipLabel);
        String safeUrl = escapeHtml(acceptUrl);
        StringBuilder scopes = new StringBuilder();
        if (scopeLabels != null) {
            for (String label : scopeLabels) {
                scopes.append("<li>").append(escapeHtml(label)).append("</li>");
            }
        }
        String expiry = expiresAt == null ? "" : DateTimeFormatter
                .ofPattern("HH:mm 'ngày' dd/MM/yyyy")
                .format(expiresAt.atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")));

        String html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Lời mời tham gia NutriMom</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 560px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #6755a5 0%%, #7a68b8 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 6px 0 0; opacity: 0.9; font-size: 14px; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .cta { text-align: center; margin: 28px 0; }
                .btn { display: inline-block; background: #6755a5; color: #ffffff !important; text-decoration: none; font-weight: 700; font-size: 16px; padding: 14px 34px; border-radius: 10px; }
                .scopes { background: #eeeafa; border: 1px solid #d6cdf0; border-radius: 10px; padding: 14px 18px; margin: 20px 0; }
                .scopes ul { margin: 8px 0 0; padding-left: 20px; color: #4a3b7a; font-size: 14px; }
                .fallback { background: #f1f5f9; padding: 12px 14px; border-radius: 8px; font-size: 12px; color: #475569; word-break: break-all; }
                .note { font-size: 13px; color: #64748b; margin-top: 22px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header">
                  <h1>NutriMom</h1>
                  <p>Đồng hành cùng hành trình thai kỳ trọn vẹn</p>
                </div>
                <div class="content">
                  <p>Xin chào,</p>
                  <p><strong>%s</strong> mời bạn tham gia nhóm gia đình trên NutriMom với vai trò
                     <strong>%s</strong>, để cùng theo dõi và chăm sóc thai kỳ.</p>
                  <div class="scopes">
                    <strong>Bạn sẽ được xem:</strong>
                    <ul>%s</ul>
                  </div>
                  <div class="cta">
                    <a href="%s" class="btn" target="_blank">Chấp nhận lời mời</a>
                  </div>
                  <p style="font-size: 13px; color: #475569;">Nếu nút trên không bấm được, hãy mở liên kết sau:</p>
                  <div class="fallback">%s</div>
                  <div class="note">
                    ⏱️ Lời mời có hiệu lực tới <strong>%s</strong>.<br>
                    🔐 Hãy <strong>đăng nhập bằng chính tài khoản gắn với email/số điện thoại đã nhận lời mời này</strong> — lời mời chỉ dành riêng cho tài khoản đó.<br>
                    Nếu bạn không quen người gửi, hãy bỏ qua email này.
                  </div>
                </div>
                <div class="footer">
                  © 2026 NutriMom Health & Nutrition. Mọi quyền được bảo lưu.
                </div>
              </div>
            </body>
            </html>
            """.formatted(safeInviter, safeRelationship, scopes.toString(), safeUrl, safeUrl, expiry);

        return html;
    }

    /**
     * Che địa chỉ email trước khi ghi log: giữ ký tự đầu và domain, đủ để đối chiếu khi hỗ trợ
     * người dùng mà không chép nguyên một định danh cá nhân vào log.
     */
    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Chặn header injection: tiêu đề email không được chứa ký tự xuống dòng. */
    private static String singleLine(String value) {
        return value.replaceAll("[\r\n]+", " ").trim();
    }

    /**
     * Chặn HTML injection từ dữ liệu người dùng nhập.
     *
     * <p>Template ở đây là text block cộng {@code String.formatted}, không phải template engine có
     * auto-escape, nên việc này phải làm bằng tay ở mọi chỗ nội suy.</p>
     */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
