package vn.nutrimom.common.email;

import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    public EmailService(@Autowired(required = false) JavaMailSender mailSender,
                        @Value("${app.mail.from:}") String fromEmail,
                        @Value("${spring.mail.username:}") String mailUsername) {
        this.mailSender = Optional.ofNullable(mailSender);
        if (fromEmail != null && !fromEmail.isBlank()) {
            this.fromEmail = fromEmail;
        } else if (mailUsername != null && !mailUsername.isBlank()) {
            this.fromEmail = mailUsername;
        } else {
            this.fromEmail = "no-reply@nutrimom.vn";
        }
    }

    public boolean sendHtml(String to, String subject, String htmlContent) {
        if (mailSender.isEmpty()) {
            log.info("[MOCK EMAIL] MailSender chưa được cấu hình. Gửi tới: {}\nTiêu đề: {}\nNội dung:\n{}",
                    to, subject, htmlContent);
            return false;
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
            log.info("Đã gửi email thành công tới: {}", to);
            return true;
        } catch (Exception e) {
            log.warn("Không thể gửi email tới {}: {}. Vui lòng kiểm tra cấu hình SMTP.", to, e.getMessage());
            return false;
        }
    }

    public boolean sendEmailOtp(String toEmail, String code, String directLink) {
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

    public boolean sendMagicLink(String toEmail, String magicLinkUrl) {
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

    public boolean sendRegistrationConfirmation(String toEmail, String displayName, String code) {
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
    public boolean sendFamilyInvitation(String toEmail, String inviterName,
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
