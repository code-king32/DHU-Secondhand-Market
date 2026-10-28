package com.dhu.secondhand.identity.service;

import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.identity.dto.SendRegistrationCodeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MailDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(MailDeliveryService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MailService mail;
    private final MailCodeCipher cipher;
    private final Clock clock;
    private final int maxAttempts;
    private final int retryDelay;

    public MailDeliveryService(JdbcTemplate jdbc, PlatformTransactionManager manager, MailService mail,
            MailCodeCipher cipher, Clock clock, @Value("${app.mail.max-attempts:3}") int maxAttempts,
            @Value("${app.mail.retry-delay-seconds:15}") int retryDelay) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.mail = mail;
        this.cipher = cipher;
        this.clock = clock;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = Math.max(1, retryDelay);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SendRegistrationCodeResponse attempt(String id) {
        Claim claim = tx.execute(status -> claim(id));
        if (claim == null) return status(id);
        String result = "SENT";
        try {
            mail.sendVerificationCode(claim.delivery.email, cipher.decrypt(claim.delivery.encrypted, id));
        } catch (MailException exception) {
            result = exception instanceof MailAuthenticationException || claim.delivery.attempts >= maxAttempts
                    ? "FAILED" : "PENDING";
            // 不记录 SMTP 异常文本，避免第三方服务回显邮箱、授权码或邮件正文。
            log.warn("Mail delivery {} attempt {}: {}", id, claim.delivery.attempts, exception.getClass().getSimpleName());
        } catch (IllegalStateException exception) {
            result = "FAILED";
            log.error("Mail delivery {} could not decrypt its payload", id);
        }
        String outcome = result;
        try {
            tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE app_mail_delivery SET status=?,code_cipher=?,next_attempt_at=?,lease_until=NULL,lease_token=NULL
                    WHERE id=? AND status='SENDING' AND lease_token=?
                    """, outcome, "PENDING".equals(outcome) ? claim.delivery.encrypted : null,
                    now().plusSeconds((long) retryDelay * claim.delivery.attempts), id, claim.token));
            return status(id);
        } catch (org.springframework.dao.DataAccessException exception) {
            // 邮件可能已送达，不能回滚或换码；租约到期后继续使用原任务恢复。
            log.error("Mail delivery {} result could not be persisted; its lease will recover", id);
            return view(claim.delivery, "SENDING");
        }
    }

    private Claim claim(String id) {
        Delivery initial = find(id);
        if (initial == null) return null;
        // 所有涉及验证码的事务统一先锁邮箱记录，再锁任务，防止重发与后台任务死锁。
        var codes = jdbc.query("""
                SELECT code_hash,consumed_at FROM registration_email_code WHERE email=? FOR UPDATE
                """, (rs, row) -> new Code(rs.getString(1), rs.getObject(2, LocalDateTime.class)), initial.email);
        var rows = jdbc.query("SELECT * FROM app_mail_delivery WHERE id=? FOR UPDATE", this::map, id);
        if (rows.isEmpty()) return null;
        Delivery job = rows.get(0);
        LocalDateTime now = now();
        if (!job.status.equals("PENDING") && !job.status.equals("SENDING")) return null;
        if (!job.expires.isAfter(now) || codes.isEmpty() || codes.get(0).consumed != null
                || !job.hash.equals(codes.get(0).hash)) {
            jdbc.update("UPDATE app_mail_delivery SET status=?,code_cipher=NULL WHERE id=?",
                    job.expires.isAfter(now) ? "CANCELLED" : "EXPIRED", id);
            return null;
        }
        if (job.status.equals("SENDING") && job.lease != null && job.lease.isAfter(now)
                || job.status.equals("PENDING") && job.next.isAfter(now)) return null;
        if (job.attempts >= maxAttempts) {
            jdbc.update("UPDATE app_mail_delivery SET status='FAILED',code_cipher=NULL WHERE id=?", id);
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE app_mail_delivery SET status='SENDING',attempts=attempts+1,lease_until=?,lease_token=? WHERE id=?",
                now.plusSeconds(120), token, id);
        return new Claim(new Delivery(job.id, job.email, job.hash, job.encrypted, "SENDING", job.attempts + 1,
                job.next, now.plusSeconds(120), job.expires), token);
    }

    public SendRegistrationCodeResponse status(String id) {
        if (!id.matches("[0-9a-f]{32}")) throw new ApiException(HttpStatus.NOT_FOUND, "发送记录不存在");
        Delivery job = find(id);
        if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "发送记录不存在");
        return view(job, job.expires.isAfter(now()) ? job.status : "EXPIRED");
    }

    private SendRegistrationCodeResponse view(Delivery job, String status) {
        String message = switch (status) {
            case "SENT" -> "验证码已发送";
            case "PENDING", "SENDING" -> "邮件正在发送，系统会自动重试；若已收到验证码，可直接填写";
            case "FAILED" -> "邮件发送结果未确认，请先检查邮箱；未收到可稍后重新获取";
            case "EXPIRED" -> "验证码已过期，请重新获取";
            default -> "该发送任务已取消，请使用最新验证码";
        };
        return new SendRegistrationCodeResponse(message,
                Math.max(0, Duration.between(now(), job.expires).getSeconds()), 60, job.id, status);
    }

    public void processDue() {
        LocalDateTime now = now();
        var ids = jdbc.queryForList("""
                SELECT id FROM app_mail_delivery
                WHERE (status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_until<=?)
                ORDER BY created_at LIMIT 20
                """, String.class, now, now);
        for (String id : ids) {
            try { attempt(id); }
            catch (RuntimeException exception) { log.error("Mail delivery {} processing failed: {}", id, exception.getClass().getSimpleName()); }
        }
        jdbc.update("DELETE FROM app_mail_delivery WHERE expires_at<?", now.minusDays(7));
    }

    private Delivery find(String id) {
        var jobs = jdbc.query("SELECT * FROM app_mail_delivery WHERE id=?", this::map, id);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    private Delivery map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Delivery(rs.getString("id"), rs.getString("email"), rs.getString("code_hash"),
                rs.getString("code_cipher"), rs.getString("status"), rs.getInt("attempts"),
                rs.getObject("next_attempt_at", LocalDateTime.class), rs.getObject("lease_until", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
    private record Code(String hash, LocalDateTime consumed) { }
    private record Delivery(String id, String email, String hash, String encrypted, String status, int attempts,
                            LocalDateTime next, LocalDateTime lease, LocalDateTime expires) { }
    private record Claim(Delivery delivery, String token) { }
}
