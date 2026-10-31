package com.dhu.secondhand.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dhu.secondhand.account.entity.User;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.identity.dto.SendRegistrationCodeResponse;
import com.dhu.secondhand.identity.exception.RegistrationCodeException;
import com.dhu.secondhand.identity.mapper.RegistrationEmailCodeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class RegistrationCodeService {

    private static final Set<String> ALLOWED_DOMAINS = Set.of("mail.dhu.edu.cn", "dhu.edu.cn");
    private static final long TTL_SECONDS = 300;
    private static final long COOLDOWN_SECONDS = 60;

    private final RegistrationEmailCodeMapper codeMapper;
    private final UserMapper userMapper;
    private final MailDeliveryService deliveries;
    private final MailCodeCipher cipher;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final VerificationCodeHasher hasher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RegistrationCodeService(RegistrationEmailCodeMapper codeMapper, UserMapper userMapper,
                                   MailDeliveryService deliveries, MailCodeCipher cipher,
                                   PlatformTransactionManager manager, JdbcTemplate jdbc,
                                   VerificationCodeHasher hasher, Clock clock) {
        this.codeMapper = codeMapper;
        this.userMapper = userMapper;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.tx = new TransactionTemplate(manager);
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SendRegistrationCodeResponse send(String rawEmail) {
        String email = normalizeCampusEmail(rawEmail);
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getEmail, email)) > 0) {
            throw new RegistrationCodeException(HttpStatus.CONFLICT, "该邮箱已注册");
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String hash = hasher.hash(code);
        String encrypted = cipher.encrypt(code, id);
        tx.executeWithoutResult(transaction -> {
            // 短事务同时提交验证码与投递任务，不在持锁期间等待 SMTP。
            codeMapper.ensureExists(email, LocalDateTime.now(clock).withNano(0));
            LocalDateTime lastSentAt = codeMapper.lockLastSentAt(email);
            // 注册也先锁同一邮箱记录；等待锁期间完成的注册必须阻止继续换码和投递。
            if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getEmail, email)) > 0) {
                throw new RegistrationCodeException(HttpStatus.CONFLICT, "该邮箱已注册");
            }
            LocalDateTime now = LocalDateTime.now(clock).withNano(0);
            if (lastSentAt != null && now.isBefore(lastSentAt.plusSeconds(COOLDOWN_SECONDS))) {
                long remaining = Duration.between(now, lastSentAt.plusSeconds(COOLDOWN_SECONDS)).getSeconds();
                throw new RegistrationCodeException(HttpStatus.TOO_MANY_REQUESTS,
                        "发送过于频繁，请稍后重试", remaining);
            }

            int updated = codeMapper.replaceCode(email, hash, now.plusSeconds(TTL_SECONDS), now);
            if (updated != 1) {
                throw new IllegalStateException("验证码记录更新失败");
            }

            jdbc.update("""
                    UPDATE app_mail_delivery SET status='CANCELLED',code_cipher=NULL
                    WHERE email=? AND status IN ('PENDING','SENDING')
                    """, email);
            jdbc.update("""
                    INSERT INTO app_mail_delivery(id,email,code_hash,code_cipher,status,attempts,next_attempt_at,expires_at,created_at)
                    VALUES(?,?,?,?,'PENDING',0,?,?,?)
                    """, id, email, hash, encrypted, now, now.plusSeconds(TTL_SECONDS), now);
        });
        return deliveries.attempt(id);
    }

    private String normalizeCampusEmail(String rawEmail) {
        if (rawEmail == null) {
            throw new RegistrationCodeException(HttpStatus.BAD_REQUEST, "请填写校园邮箱");
        }
        String email = rawEmail.strip().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        if (email.length() > 254 || at <= 0 || at != email.lastIndexOf('@')
                || !ALLOWED_DOMAINS.contains(email.substring(at + 1))) {
            throw new RegistrationCodeException(HttpStatus.BAD_REQUEST, "请使用东华大学学生或教工邮箱");
        }
        return email;
    }
}
