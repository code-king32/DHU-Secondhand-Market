package com.dhu.secondhand.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Component
public class AbuseProtection {
    private final Clock clock;
    private final boolean enabled;
    private final int loginIp, loginAccount, loginWindow, mailHour, mailDay, emailDay, globalDay;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public AbuseProtection(Clock clock, @Value("${app.abuse.enabled:true}") boolean enabled,
            @Value("${app.abuse.login-ip-limit:30}") int loginIp,
            @Value("${app.abuse.login-account-limit:5}") int loginAccount,
            @Value("${app.abuse.login-window-seconds:600}") int loginWindow,
            @Value("${app.abuse.mail-ip-hour-limit:20}") int mailHour,
            @Value("${app.abuse.mail-ip-day-limit:100}") int mailDay,
            @Value("${app.abuse.mail-email-day-limit:10}") int emailDay,
            @Value("${app.abuse.mail-global-day-limit:1000}") int globalDay) {
        this.clock = clock;
        this.enabled = enabled;
        this.loginIp = Math.max(1, loginIp);
        this.loginAccount = Math.max(1, loginAccount);
        this.loginWindow = Math.max(1, loginWindow);
        this.mailHour = Math.max(1, mailHour);
        this.mailDay = Math.max(1, mailDay);
        this.emailDay = Math.max(1, emailDay);
        this.globalDay = Math.max(1, globalDay);
    }

    public synchronized void beforeLogin(String ip, String email) {
        if (!enabled) return;
        consume(new Rule("login-ip:" + digest(ip), loginIp, loginWindow),
                new Rule("login-account:" + digest(normalize(email)), loginAccount, loginWindow));
    }

    public synchronized void loginSucceeded(String email) {
        buckets.remove("login-account:" + digest(normalize(email)));
    }

    public synchronized void beforeMail(String ip, String email) {
        if (!enabled) return;
        consume(new Rule("mail-hour:" + digest(ip), mailHour, 3600),
                new Rule("mail-day:" + digest(ip), mailDay, 86400),
                new Rule("mail-email:" + digest(normalize(email)), emailDay, 86400),
                new Rule("mail-global", globalDay, 86400));
    }

    private void consume(Rule... rules) {
        long now = clock.instant().getEpochSecond();
        buckets.entrySet().removeIf(entry -> entry.getValue().until <= now);
        if (buckets.size() + rules.length > 10000) {
            throw new RateLimitException("请求过多，请稍后重试", 60);
        }
        for (Rule rule : rules) {
            Bucket bucket = buckets.get(rule.key);
            if (bucket != null && bucket.count >= rule.limit) {
                throw new RateLimitException("操作过于频繁，请稍后重试", bucket.until - now);
            }
        }
        for (Rule rule : rules) {
            Bucket bucket = buckets.computeIfAbsent(rule.key, key -> new Bucket(now + rule.seconds));
            bucket.count++;
        }
    }

    private String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private record Rule(String key, int limit, long seconds) { }
    private static final class Bucket {
        final long until;
        int count;
        Bucket(long until) { this.until = until; }
    }
}
