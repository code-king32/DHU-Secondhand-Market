package com.dhu.secondhand.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dhu.secondhand.account.dto.UserView;
import com.dhu.secondhand.account.entity.User;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.common.TextValidation;
import com.dhu.secondhand.common.AbuseProtection;
import com.dhu.secondhand.identity.dto.LoginRequest;
import com.dhu.secondhand.identity.dto.RegisterRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class AccountAuthService {
    private final UserMapper users;
    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    private final VerificationCodeHasher codes;
    private final Clock clock;
    private final AbuseProtection protection;

    public AccountAuthService(UserMapper users, JdbcTemplate jdbc, PasswordService passwords,
                              VerificationCodeHasher codes, Clock clock, AbuseProtection protection) {
        this.users = users;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.codes = codes;
        this.clock = clock;
        this.protection = protection;
    }

    // 业务拒绝前仅允许更新失败次数；必须提交该计数以防反复猜码。
    @Transactional(noRollbackFor = ApiException.class)
    public UserView register(RegisterRequest request) {
        String email = campusEmail(request.email());
        passwords.validate(request.password());
        String nickname = request.nickname().strip();
        if (TextValidation.isBlank(nickname) || nickname.length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "昵称长度须为1至50个字符");
        }
        var rows = jdbc.query("""
                SELECT id, code_hash, expires_at, failed_attempts, consumed_at
                FROM registration_email_code WHERE email=? FOR UPDATE
                """, (rs, row) -> new CodeState(rs.getLong("id"), rs.getString("code_hash"),
                rs.getObject("expires_at", LocalDateTime.class), rs.getInt("failed_attempts"),
                rs.getObject("consumed_at", LocalDateTime.class)), email);
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        if (rows.isEmpty() || rows.get(0).hash().isEmpty() || rows.get(0).consumed() != null
                || !rows.get(0).expires().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "验证码无效或已过期，请重新获取");
        }
        CodeState state = rows.get(0);
        if (state.failed() >= 5) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "验证码错误次数过多，请重新获取");
        }
        if (!codes.matches(request.code(), state.hash())) {
            jdbc.update("UPDATE registration_email_code SET failed_attempts=failed_attempts+1 WHERE id=?", state.id());
            throw new ApiException(state.failed() >= 4 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST,
                    "验证码错误，请检查邮件");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwords.hash(request.password()));
        user.setNickname(nickname);
        user.setEmailVerified(1);
        user.setStatus(0);
        user.setRole("USER");
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "该邮箱已注册");
        }
        int consumed = jdbc.update("UPDATE registration_email_code SET consumed_at=? WHERE id=? AND consumed_at IS NULL",
                now, state.id());
        if (consumed != 1) {
            throw new IllegalStateException("验证码消费失败");
        }
        return UserView.from(users.selectById(user.getId()));
    }

    public UserView login(LoginRequest request) {
        String email = campusEmail(request.email());
        User user = users.selectOne(Wrappers.<User>lambdaQuery().eq(User::getEmail, email));
        // 以数据库中实际命中的账号计数，避免 MySQL 不区分重音的邮箱别名绕过账号限额。
        protection.beforeLoginAccount(user == null ? email : user.getEmail());
        boolean matches = passwords.matches(request.password(), user == null ? null : user.getPasswordHash());
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        if (user.getStatus() != 0 || user.getEmailVerified() != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号当前不可用");
        }
        return UserView.from(user);
    }

    @Transactional
    public UserView openSession(long userId, HttpServletRequest request) {
        int version = jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=? FOR UPDATE",
                Integer.class, userId);
        User user = users.selectById(userId);
        if (user.getStatus() != 0 || user.getEmailVerified() != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号当前不可用");
        }
        var old = request.getSession(false);
        if (old != null) {
            try { old.invalidate(); } catch (IllegalStateException ignored) { }
        }
        var session = request.getSession(true);
        session.setAttribute("userId", userId);
        session.setAttribute("sessionVersion", version);
        return UserView.from(user);
    }

    private String campusEmail(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || email.length() > 254
                || !Set.of("mail.dhu.edu.cn", "dhu.edu.cn").contains(email.substring(at + 1))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请使用东华大学学生或教工邮箱");
        }
        return email;
    }

    private record CodeState(long id, String hash, LocalDateTime expires, int failed,
                             LocalDateTime consumed) { }
}
