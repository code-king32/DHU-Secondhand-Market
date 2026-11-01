package com.dhu.secondhand.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SessionAuth {
    private static final String VERIFIED_ACTOR = SessionAuth.class.getName() + ".actor";
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
        request.setAttribute(VERIFIED_ACTOR, new VerifiedActor(access.id(), expected, session));
        return access;
    }

    /** 调用者必须已在当前事务中持有该账号行锁。非 HTTP 的内部调用继续使用原有账号校验。 */
    public void verifyLockedActor(long actor) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) return;
        var request = servlet.getRequest();
        Object saved = request.getAttribute(VERIFIED_ACTOR);
        // Spring tests can bind a synthetic request even for direct service calls. Only
        // requests dispatched to an MVC handler require a captured HTTP identity.
        if (saved == null && request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) == null) return;
        if (!(saved instanceof VerifiedActor verified) || verified.id() != actor) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
        }
        try {
            HttpSession currentSession = request.getSession(false);
            if (currentSession == null || !currentSession.getId().equals(verified.session().getId())
                    || !(verified.session().getAttribute("userId") instanceof Number current)
                    || current.longValue() != actor
                    || sessionVersion(verified.session()) != verified.version()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
            }
        } catch (IllegalStateException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
        }
        Integer version = jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=? FOR UPDATE", Integer.class, actor);
        if (version == null || version != verified.version()) {
            invalidate(verified.session());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
        }
    }

    private int sessionVersion(HttpSession session) {
        Object version = session.getAttribute("sessionVersion");
        return version instanceof Number number ? number.intValue() : 0;
    }

    private record VerifiedActor(long id, int version, HttpSession session) { }

    private void invalidate(HttpSession session) {
        try { session.invalidate(); } catch (IllegalStateException ignored) { }
    }

    private record Access(long id, int status, int verified, String role, int version) { }
}
