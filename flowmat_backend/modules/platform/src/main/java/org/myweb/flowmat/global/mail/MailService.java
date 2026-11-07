package org.myweb.flowmat.global.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Async
    public void sendProjectInvite(String toEmail, String projectName, String inviterName,
                                  String inviteToken, String role) {
        if (fromAddress == null || fromAddress.isBlank()) {
            log.debug("Mail not configured; skipping invite email to {}", toEmail);
            return;
        }
        try {
            String acceptUrl = frontendUrl + "/invite/accept?token=" + inviteToken;
            String subject = "[FlowMat] You've been invited to join " + projectName;
            String body = buildInviteHtml(projectName, inviterName, acceptUrl, role);
            send(toEmail, subject, body);
            log.debug("Invite email sent to {}", toEmail);
        } catch (Exception e) {
            log.warn("Failed to send invite email to {}: {}", toEmail, e.getClass().getSimpleName());
        }
    }

    @Async
    public void sendEmailVerificationCode(String toEmail, String code) {
        if (fromAddress == null || fromAddress.isBlank()) {
            log.info("Mail not configured; skipping verification email to {}", toEmail);
            return;
        }
        try {
            send(toEmail, "[FlowMat] Email verification code", buildVerificationHtml(code));
        } catch (Exception e) {
            log.warn("Failed to send verification email to {}: {}", toEmail, e.getClass().getSimpleName());
        }
    }

    @Async
    public void sendPasswordResetMail(String toEmail, String token) {
        String resetUrl = frontendUrl + "/reset-password?token=" + token;
        if (fromAddress == null || fromAddress.isBlank()) {
            log.info("Mail not configured; skipping password reset email to {}", toEmail);
            return;
        }
        try {
            send(toEmail, "[FlowMat] Password reset", buildPasswordResetHtml(resetUrl));
        } catch (Exception e) {
            log.warn("Failed to send password reset email to {}: {}", toEmail, e.getClass().getSimpleName());
        }
    }

    @Async
    public void sendDormantReactivationMail(String toEmail, String token) {
        String reactivateUrl = frontendUrl + "/reactivate-account?token=" + token;
        if (fromAddress == null || fromAddress.isBlank()) {
            log.info("Mail not configured; skipping dormant reactivation email to {}", toEmail);
            return;
        }
        try {
            send(toEmail, "[FlowMat] Reactivate your account", buildDormantReactivationHtml(reactivateUrl));
        } catch (Exception e) {
            log.warn("Failed to send dormant reactivation email to {}: {}", toEmail, e.getClass().getSimpleName());
        }
    }

    private void send(String to, String subject, String htmlBody) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(message);
    }

    private String buildInviteHtml(String projectName, String inviterName, String acceptUrl, String role) {
        return """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
              <h2 style="margin-bottom:8px">You have been invited to FlowMat</h2>
              <p><strong>%s</strong> has invited you to join the project <strong>%s</strong> as <strong>%s</strong>.</p>
              <a href="%s"
                 style="display:inline-block;margin-top:16px;padding:10px 20px;
                        background:#6366f1;color:#fff;border-radius:8px;text-decoration:none">
                Accept Invitation
              </a>
              <p style="margin-top:24px;font-size:12px;color:#888">
                This link expires in 7 days. If you did not expect this invitation, you may ignore this email.
              </p>
            </div>
            """.formatted(inviterName, projectName, role, acceptUrl);
    }

    private String buildVerificationHtml(String code) {
        return """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
              <h2 style="margin-bottom:8px">Verify your email</h2>
              <p>Use the code below to continue signing up.</p>
              <div style="margin-top:16px;padding:12px 16px;background:#f4f4f5;border-radius:10px;
                          font-size:28px;font-weight:700;letter-spacing:4px;text-align:center">%s</div>
              <p style="margin-top:24px;font-size:12px;color:#888">This code expires in 5 minutes.</p>
            </div>
            """.formatted(code);
    }

    private String buildPasswordResetHtml(String resetUrl) {
        return """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
              <h2 style="margin-bottom:8px">Reset your password</h2>
              <p>Click the button below to reset your password.</p>
              <a href="%s"
                 style="display:inline-block;margin-top:16px;padding:10px 20px;
                        background:#18181b;color:#fff;border-radius:8px;text-decoration:none">
                Reset Password
              </a>
              <p style="margin-top:24px;font-size:12px;color:#888">
                This link expires in 30 minutes. If you did not request this, you can ignore this email.
              </p>
            </div>
            """.formatted(resetUrl);
    }

    private String buildDormantReactivationHtml(String reactivateUrl) {
        return """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
              <h2 style="margin-bottom:8px">Reactivate your FlowMat account</h2>
              <p>Your account is dormant. Click below to restore access.</p>
              <a href="%s"
                 style="display:inline-block;margin-top:16px;padding:10px 20px;
                        background:#0f766e;color:#fff;border-radius:8px;text-decoration:none">
                Reactivate Account
              </a>
              <p style="margin-top:24px;font-size:12px;color:#888">
                This link becomes invalid once used or when a newer reactivation email is issued.
              </p>
            </div>
            """.formatted(reactivateUrl);
    }
}
