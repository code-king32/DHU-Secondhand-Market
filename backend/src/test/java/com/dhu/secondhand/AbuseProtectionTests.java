package com.dhu.secondhand;

import com.dhu.secondhand.common.AbuseProtection;
import com.dhu.secondhand.common.RateLimitException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AbuseProtectionTests {
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-28T04:00:00Z");

    private AbuseProtection protection(int loginIp, int loginAccount, int emailDay, int globalDay) {
        when(clock.instant()).thenReturn(now);
        return new AbuseProtection(clock, true, loginIp, loginAccount, 60, 20, 100, emailDay, globalDay);
    }

    @Test
    void accountLimitSpansIpsNormalizesEmailAndExpiresAtBoundary() {
        var protection = protection(30, 2, 10, 100);
        protection.beforeLogin("ip-1", " STUDENT@MAIL.DHU.EDU.CN ");
        protection.beforeLogin("ip-2", "student@mail.dhu.edu.cn");
        var denied = assertThrows(RateLimitException.class,
                () -> protection.beforeLogin("ip-3", "student@mail.dhu.edu.cn"));
        assertEquals(60, denied.getRetryAfter());
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertDoesNotThrow(() -> protection.beforeLogin("ip-3", "student@mail.dhu.edu.cn"));
    }

    @Test
    void successfulLoginResetsAccountFailuresButDoesNotResetIpBudget() {
        var protection = protection(2, 1, 10, 100);
        protection.beforeLogin("ip-1", "student@mail.dhu.edu.cn");
        protection.loginSucceeded("STUDENT@mail.dhu.edu.cn");
        protection.beforeLogin("ip-1", "student@mail.dhu.edu.cn");
        protection.loginSucceeded("student@mail.dhu.edu.cn");
        assertThrows(RateLimitException.class, () -> protection.beforeLogin("ip-1", "other@mail.dhu.edu.cn"));
        assertDoesNotThrow(() -> protection.beforeLogin("ip-2", "student@mail.dhu.edu.cn"));
    }

    @Test
    void deniedMailDoesNotSpendOtherBudgetsAndDailyLimitsSurviveHourBoundary() {
        var protection = protection(30, 5, 2, 3);
        protection.beforeMail("ip-1", "a@mail.dhu.edu.cn");
        protection.beforeMail("ip-2", "A@MAIL.DHU.EDU.CN");
        assertThrows(RateLimitException.class, () -> protection.beforeMail("ip-3", "a@mail.dhu.edu.cn"));
        protection.beforeMail("ip-3", "b@mail.dhu.edu.cn");
        when(clock.instant()).thenReturn(now.plusSeconds(3600));
        assertThrows(RateLimitException.class, () -> protection.beforeMail("ip-4", "c@mail.dhu.edu.cn"));
        when(clock.instant()).thenReturn(now.plusSeconds(86400));
        assertDoesNotThrow(() -> protection.beforeMail("ip-4", "a@mail.dhu.edu.cn"));
    }

    @Test
    void concurrentLoginAttemptsCannotExceedAccountBudget() throws Exception {
        var protection = protection(100, 2, 10, 100);
        var executor = Executors.newFixedThreadPool(6);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 20; index++) {
                jobs.add(executor.submit(() -> {
                    try { protection.beforeLogin("ip", "same@mail.dhu.edu.cn"); return true; }
                    catch (RateLimitException exception) { return false; }
                }));
            }
            int accepted = 0;
            for (var job : jobs) if (job.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(2, accepted);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void reachingBucketCapacityDoesNotBlockAlreadyTrackedClients() {
        var protection = protection(10000, 10000, 10, 100);
        for (int index = 0; index < 5000; index++) {
            protection.beforeLogin("ip-" + index, "account-" + index);
        }
        assertDoesNotThrow(() -> protection.beforeLogin("ip-0", "account-0"));
        assertThrows(RateLimitException.class, () -> protection.beforeLogin("new-ip", "new-account"));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertDoesNotThrow(() -> protection.beforeLogin("new-ip", "new-account"));
    }
}
