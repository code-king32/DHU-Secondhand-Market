package com.dhu.secondhand.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/** Aggregates operational state without logging email addresses or verification codes. */
@Component
public class JobBacklogMonitor {
    private static final Logger log = LoggerFactory.getLogger(JobBacklogMonitor.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int retentionHours;

    public JobBacklogMonitor(JdbcTemplate jdbc, Clock clock,
            @Value("${app.images.unused-retention-hours:24}") int retentionHours) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retentionHours = Math.max(1, retentionHours);
    }

    public Snapshot snapshot() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        Backlog mail = jdbc.queryForObject("""
                SELECT COUNT(*), MIN(CASE WHEN status='PENDING' THEN next_attempt_at ELSE lease_until END)
                FROM app_mail_delivery WHERE (status='PENDING' AND next_attempt_at<=?)
                    OR (status='SENDING' AND lease_until<=?)
                """, (rs, row) -> new Backlog(rs.getLong(1), rs.getObject(2, LocalDateTime.class)), now, now);
        Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM app_mail_delivery WHERE status='FAILED'", Long.class);
        Backlog images = jdbc.queryForObject("""
                SELECT COUNT(*), MIN(COALESCE(unused_since, created_at)) FROM app_uploaded_image
                WHERE status='DELETING' OR (unused_since IS NOT NULL AND unused_since<=?)
                """, (rs, row) -> new Backlog(rs.getLong(1), rs.getObject(2, LocalDateTime.class)), now.minusHours(retentionHours));
        return new Snapshot(mail.count, age(now, mail.oldest), failed, images.count, age(now, images.oldest));
    }

    public void logBacklog() {
        Snapshot s = snapshot();
        log.info("Background backlog mail_due={} mail_oldest_due_seconds={} mail_retained_failed={} image_cleanup_due={} image_oldest_unused_seconds={}",
                s.mailDue, s.mailOldestDueSeconds, s.mailRetainedFailed, s.imageCleanupDue, s.imageOldestUnusedSeconds);
    }

    private long age(LocalDateTime now, LocalDateTime oldest) {
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).getSeconds());
    }
    private record Backlog(long count, LocalDateTime oldest) { }
    public record Snapshot(long mailDue, long mailOldestDueSeconds, long mailRetainedFailed,
                           long imageCleanupDue, long imageOldestUnusedSeconds) { }
}
