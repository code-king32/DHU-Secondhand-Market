package com.dhu.secondhand.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JsonBodyLimit extends OncePerRequestFilter {
    private final int limit;
    public JsonBodyLimit(@Value("${app.web.max-json-bytes:65536}") int limit) {
        if (limit < 1 || limit > 1048576) throw new IllegalArgumentException("Invalid JSON body limit");
        this.limit = limit;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?")) {
            chain.doFilter(request, response); return;
        }
        if (request.getContentLengthLong() > limit) { reject(response); return; }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private final ByteArrayInputStream input = new ByteArrayInputStream(body);
            private final ServletInputStream stream = new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            @Override public ServletInputStream getInputStream() { return stream; }
            @Override public BufferedReader getReader() throws IOException {
                String encoding = getCharacterEncoding();
                return new BufferedReader(new InputStreamReader(getInputStream(), encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
            }
        }, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"请求体过大\"}");
    }
}
