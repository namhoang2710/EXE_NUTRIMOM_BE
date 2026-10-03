package vn.nutrimom.common.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
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

    public boolean sendMagicLink(String toEmail, String magicLinkUrl) {
        String subject = "✨ Liên kết đăng nhập vào NutriMom";
        String html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Đăng nhập NutriMom</title>
              <style>
                body { margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; color: #1e293b; }
                .wrapper { max-width: 560px; margin: 30px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; }
                .header { background: linear-gradient(135deg, #7c3aed 0%%, #a855f7 100%%); padding: 32px 24px; text-align: center; color: #ffffff; }
                .header h1 { margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.5px; }
                .header p { margin: 6px 0 0; opacity: 0.9; font-size: 14px; }
                .content { padding: 32px 28px; line-height: 1.6; }
                .btn-container { text-align: center; margin: 30px 0; }
                .btn { display: inline-block; background: #7c3aed; color: #ffffff !important; font-weight: 600; text-decoration: none; padding: 14px 32px; border-radius: 10px; font-size: 16px; box-shadow: 0 4px 12px rgba(124, 58, 237, 0.35); }
                .note { background: #f1f5f9; padding: 14px 16px; border-radius: 8px; font-size: 13px; color: #64748b; margin-top: 24px; }
                .footer { padding: 20px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; }
                .raw-link { word-break: break-all; color: #7c3aed; font-size: 12px; }
              </style>
            </head>
            <body>
              <div class="wrapper">
                <div class="header">
                  <h1>NutriMom</h1>
                  <p>Đồng hành cùng hành trình làm mẹ trọn vẹn</p>
                </div>
                <div class="content">
                  <p>Xin chào,</p>
                  <p>Chúng tôi nhận được yêu cầu đăng nhập vào tài khoản NutriMom với email <strong>%s</strong>.</p>
                  <p>Nhấn vào nút bên dưới để đăng nhập ngay mà không cần mật khẩu:</p>
                  <div class="btn-container">
                    <a href="%s" class="btn" target="_blank">👉 Đăng nhập vào NutriMom</a>
                  </div>
                  <div class="note">
                    ⏱️ <strong>Lưu ý:</strong> Liên kết này chỉ có hiệu lực trong vòng <strong>15 phút</strong> và chỉ sử dụng được <strong>1 lần duy nhất</strong>.<br>
                    Nếu bạn không yêu cầu đăng nhập, vui lòng bỏ qua email này.
                  </div>
                  <p style="margin-top: 24px; font-size: 13px; color: #64748b;">
                    Nếu nút bấm không hoạt động, bạn có thể sao chép liên kết sau dán vào trình duyệt:<br>
                    <a href="%s" class="raw-link">%s</a>
                  </p>
                </div>
                <div class="footer">
                  © 2026 NutriMom Health & Nutrition. Mọi quyền được bảo lưu.
                </div>
              </div>
            </body>
            </html>
            """.formatted(toEmail, magicLinkUrl, magicLinkUrl, magicLinkUrl);

        return sendHtml(toEmail, subject, html);
    }
}
