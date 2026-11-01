package com.dhu.secondhand;

import com.dhu.secondhand.common.BackgroundJobs;
import com.dhu.secondhand.common.JobBacklogMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BackgroundJobsTests {
    @Test
    void snapshotCountsDueWorkAndRetainedFailuresWithoutCountingFutureJobs() {
        var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("CREATE TABLE app_mail_delivery (id VARCHAR(32) PRIMARY KEY, email VARCHAR(254), code_hash VARCHAR(100), status VARCHAR(16), next_attempt_at TIMESTAMP, lease_until TIMESTAMP, expires_at TIMESTAMP, created_at TIMESTAMP)");
            jdbc.execute("CREATE TABLE app_uploaded_image (name VARCHAR(40) PRIMARY KEY, owner_id BIGINT, byte_size BIGINT, created_at TIMESTAMP, unused_since TIMESTAMP, status VARCHAR(16))");
            var now = LocalDateTime.of(2026, 1, 1, 12, 0);
            jdbc.update("INSERT INTO app_mail_delivery(id,email,code_hash,status,next_attempt_at,expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                    "a", "a", "h", "PENDING", now.minusSeconds(90), now.plusMinutes(5), now.minusMinutes(2));
            jdbc.update("INSERT INTO app_mail_delivery(id,email,code_hash,status,next_attempt_at,expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                    "b", "b", "h", "PENDING", now.plusMinutes(1), now.plusMinutes(5), now);
            jdbc.update("INSERT INTO app_mail_delivery(id,email,code_hash,status,next_attempt_at,expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                    "c", "c", "h", "FAILED", now, now.plusMinutes(5), now);
            jdbc.update("INSERT INTO app_uploaded_image(name,owner_id,byte_size,created_at,unused_since,status) VALUES(?,?,?,?,?,?)",
                    "old", 1, 1, now.minusHours(30), now.minusHours(25), "ACTIVE");
            jdbc.update("INSERT INTO app_uploaded_image(name,owner_id,byte_size,created_at,unused_since,status) VALUES(?,?,?,?,?,?)",
                    "recent", 1, 1, now, now, "ACTIVE");
            var monitor = new JobBacklogMonitor(jdbc, Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC), 24);
            var result = monitor.snapshot();
            assertEquals(1, result.mailDue());
            assertEquals(90, result.mailOldestDueSeconds());
            assertEquals(1, result.mailRetainedFailed());
            assertEquals(1, result.imageCleanupDue());
            assertEquals(25 * 3600, result.imageOldestUnusedSeconds());
        } finally { database.shutdown(); }
    }

    @Test
    void blockedMailSchedulerDoesNotDelayMaintenance() throws Exception {
        var jobs = new BackgroundJobs(mock(com.dhu.secondhand.identity.service.MailDeliveryService.class),
                mock(com.dhu.secondhand.marketplace.ImageStorage.class), mock(JobBacklogMonitor.class));
        var mail = jobs.mailTaskScheduler();
        var maintenance = jobs.maintenanceTaskScheduler();
        mail.initialize(); maintenance.initialize();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ran = new CountDownLatch(1);
        try {
            mail.execute(() -> { started.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            maintenance.execute(ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); mail.shutdown(); maintenance.shutdown(); }
    }
}
