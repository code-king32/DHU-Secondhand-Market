package com.dhu.secondhand.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionAuth {
    private final JdbcTemplate jdbc;

    public SessionAuth(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long requireUserId(HttpServletRequest request) {
        return load(request).id();
    }

    public long requireAdminId(HttpServletRequest request) {
        Access access = load(request);
        if (!"ADMIN".equals(access.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return access.id();
    }

    private Access load(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object userId;
        Object sessionVersion;
        try {
            userId = session == null ? null : session.getAttribute("userId");
            sessionVersion = session == null ? null : session.getAttribute("sessionVersion");
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效");
        }
        if (!(userId instanceof Number number)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
        }
        var rows = jdbc.query("SELECT id, status, email_verified, role, session_version FROM app_user WHERE id=?",
                (rs, row) -> new Access(rs.getLong("id"), rs.getInt("status"),
                        rs.getInt("email_verified"), rs.getString("role"), rs.getInt("session_version")), number.longValue());
        if (rows.isEmpty()) {
            invalidate(session);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效");
        }
        Access access = rows.get(0);
        if (access.status() != 0 || access.verified() != 1) {
            invalidate(session);
            throw new ApiException(HttpStatus.FORBIDDEN, "账号当前不可用");
        }
        int expected = sessionVersion instanceof Number version ? version.intValue() : 0;
        if (access.version() != expected) {
            invalidate(session);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
        }
        return access;
    }

    private void invalidate(HttpSession session) {
        try { session.invalidate(); } catch (IllegalStateException ignored) { }
    }

    private record Access(long id, int status, int verified, String role, int version) { }
}
