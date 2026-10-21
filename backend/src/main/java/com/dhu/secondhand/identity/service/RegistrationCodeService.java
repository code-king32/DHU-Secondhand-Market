package com.dhu.secondhand.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dhu.secondhand.account.entity.User;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.identity.dto.SendRegistrationCodeResponse;
import com.dhu.secondhand.identity.exception.RegistrationCodeException;
import com.dhu.secondhand.identity.mapper.RegistrationEmailCodeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class RegistrationCodeService {

    private static final Set<String> ALLOWED_DOMAINS = Set.of("mail.dhu.edu.cn", "dhu.edu.cn");
    private static final long TTL_SECONDS = 300;
    private static final long COOLDOWN_SECONDS = 60;

    private final RegistrationEmailCodeMapper codeMapper;
    private final UserMapper userMapper;
    private final MailService mailService;
    private final VerificationCodeHasher hasher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RegistrationCodeService(RegistrationEmailCodeMapper codeMapper, UserMapper userMapper,
                                   MailService mailService, VerificationCodeHasher hasher, Clock clock) {
        this.codeMapper = codeMapper;
        this.userMapper = userMapper;
        this.mailService = mailService;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Transactional
    public SendRegistrationCodeResponse send(String rawEmail) {
        String email = normalizeCampusEmail(rawEmail);
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getEmail, email)) > 0) {
            throw new RegistrationCodeException(HttpStatus.CONFLICT, "该邮箱已注册");
        }

        // 唯一索引和 upsert 取得同一邮箱记录的排他锁，直到事务结束。
        codeMapper.ensureExists(email, LocalDateTime.now(clock).withNano(0));
        LocalDateTime lastSentAt = codeMapper.lockLastSentAt(email);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (lastSentAt != null && now.isBefore(lastSentAt.plusSeconds(COOLDOWN_SECONDS))) {
            long remaining = Duration.between(now, lastSentAt.plusSeconds(COOLDOWN_SECONDS)).getSeconds();
            throw new RegistrationCodeException(HttpStatus.TOO_MANY_REQUESTS,
                    "发送过于频繁，请稍后重试", remaining);
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        int updated = codeMapper.replaceCode(email, hasher.hash(code), now.plusSeconds(TTL_SECONDS), now);
        if (updated != 1) {
            throw new IllegalStateException("验证码记录更新失败");
        }

        try {
            mailService.sendVerificationCode(email, code);
        } catch (MailException exception) {
            // 抛出运行时异常，回滚本次验证码及发送时间的修改。
            throw new RegistrationCodeException(HttpStatus.BAD_GATEWAY, "邮件发送失败，请稍后重试");
        }
        return new SendRegistrationCodeResponse("验证码已发送", TTL_SECONDS, COOLDOWN_SECONDS);
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
