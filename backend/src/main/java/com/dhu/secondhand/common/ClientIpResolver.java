package com.dhu.secondhand.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClientIpResolver {
    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${app.web.trusted-proxies:}") String configured) {
        trustedProxies = Arrays.stream(configured.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).map(value -> {
                    String address = literal(value);
                    if (address == null) throw new IllegalArgumentException("app.web.trusted-proxies 必须填写代理的 IP 地址");
                    return address;
                }).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (peer == null || !trustedProxies.contains(peer)) return request.getRemoteAddr();
        var headers = request.getHeaders("X-Forwarded-For");
        if (headers == null || !headers.hasMoreElements()) return peer;
        String forwarded = headers.nextElement();
        // 代理应输出一个完整的地址链；含糊或畸形的输入不参与限流身份计算。
        if (headers.hasMoreElements() || forwarded.length() > 4096) return peer;
        String[] chain = forwarded.split(",", -1);
        for (int index = 0; index < chain.length; index++) {
            chain[index] = literal(chain[index].strip());
            if (chain[index] == null) return peer;
        }
        String current = peer;
        for (int index = chain.length - 1; index >= 0 && trustedProxies.contains(current); index--) {
            current = chain[index];
        }
        return current;
    }

    private static String literal(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) return null;
            }
        }
        try {
            // 仅接受字面 IP，避免对客户端输入执行 DNS 查询。
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
