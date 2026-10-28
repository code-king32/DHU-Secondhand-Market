package com.dhu.secondhand.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OriginGuard extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final Set<String> allowedOrigins;

    public OriginGuard(@Value("${app.web.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        allowedOrigins = Arrays.stream(origins.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 对所有写请求检查来源，避免编码路径或分号路径参数绕过检查。
        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        String referer = request.getHeader("Referer");
        boolean denied = "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                || (origin != null ? !trusted(origin, request)
                    : referer != null && !trusted(referer, request));
        String contentType = request.getContentType();
        if (!denied && contentType != null) {
            try {
                MediaType parsed = MediaType.parseMediaType(contentType);
                boolean json = "application".equalsIgnoreCase(parsed.getType())
                        && "json".equalsIgnoreCase(parsed.getSubtype());
                boolean imageUpload = "POST".equals(request.getMethod())
                        && (request.getContextPath() + "/api/images").equals(request.getRequestURI())
                        && "multipart".equalsIgnoreCase(parsed.getType())
                        && "form-data".equalsIgnoreCase(parsed.getSubtype());
                denied = !json && !imageUpload;
            } catch (IllegalArgumentException exception) {
                denied = true;
            }
        }
        if (denied) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"请求来源或内容类型不受信任\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean trusted(String source, HttpServletRequest request) {
        try {
            URI uri = URI.create(source);
            if (uri.getUserInfo() != null || uri.getHost() == null
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                return false;
            }
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            String normalized = uri.getScheme() + "://" + uri.getHost()
                    + ((port == 80 && "http".equals(uri.getScheme()))
                    || (port == 443 && "https".equals(uri.getScheme())) ? "" : ":" + port);
            return allowedOrigins.contains(normalized) || uri.getScheme().equals(request.getScheme())
                    && uri.getHost().equalsIgnoreCase(request.getServerName()) && port == request.getServerPort();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
