package com.dhu.secondhand.account.controller;

import com.dhu.secondhand.account.dto.UserView;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.account.service.AccountClosureGuard;
import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.common.SessionAuth;
import com.dhu.secondhand.identity.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final SessionAuth auth;
    private final UserMapper users;
    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    private final List<AccountClosureGuard> guards;
    private final TransactionTemplate transactions;

    public AccountController(SessionAuth auth, UserMapper users, JdbcTemplate jdbc,
                             PasswordService passwords, List<AccountClosureGuard> guards,
                             TransactionTemplate transactions) {
        this.auth=auth; this.users=users; this.jdbc=jdbc; this.passwords=passwords;
        this.guards=guards; this.transactions=transactions;
    }

    public record ProfileRequest(@NotBlank @Size(max=50) String nickname) { }
    public record CloseRequest(@NotBlank @Size(max=72) String password) { }
    public record PublicProfile(long id, String nickname, boolean emailVerified, LocalDateTime createdAt) { }

    @PatchMapping("/account/me")
    public UserView update(@Valid @RequestBody ProfileRequest body, HttpServletRequest request) {
        long id = auth.requireUserId(request);
        String nickname = body.nickname().strip();
        if (nickname.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "昵称不能为空");
        int updated = jdbc.update("UPDATE app_user SET nickname=? WHERE id=? AND status=0", nickname, id);
        if (updated != 1) throw new ApiException(HttpStatus.CONFLICT, "账号状态已发生变化");
        return UserView.from(users.selectById(id));
    }

    @DeleteMapping("/account/me")
    public ResponseEntity<Void> close(@Valid @RequestBody CloseRequest body, HttpServletRequest request) {
        long id = auth.requireUserId(request);
        transactions.executeWithoutResult(transaction -> {
            jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class, id);
            // 等待账号锁期间可能发生禁用、注销或会话撤销，必须在锁内重新校验。
            if (auth.requireUserId(request) != id) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
            }
            var user = users.selectById(id);
            if (!passwords.matches(body.password(), user.getPasswordHash())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "密码错误");
            }
            if ("ADMIN".equals(user.getRole())) {
                throw new ApiException(HttpStatus.CONFLICT, "管理员账号不能自行注销");
            }
            guards.forEach(guard -> guard.checkCanClose(id));
            jdbc.update("UPDATE app_user SET status=2, password_hash='', nickname='已注销用户', session_version=session_version+1 WHERE id=?", id);
            guards.forEach(guard -> guard.onClosed(id));
        });
        // 注销已经提交，并发退出造成的 Session 消失或失效不应把成功响应变成 500。
        var session = request.getSession(false);
        if (session != null) {
            try { session.invalidate(); } catch (IllegalStateException ignored) { }
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{id}")
    public PublicProfile profile(@PathVariable long id) {
        var user = users.selectById(id);
        if (user == null || user.getStatus() != 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return new PublicProfile(id, user.getNickname(), user.getEmailVerified()==1, user.getCreatedAt());
    }
}
