package com.dhu.secondhand.common;

import com.dhu.secondhand.identity.service.MailDeliveryService;
import com.dhu.secondhand.marketplace.ImageStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundJobs {
    private final MailDeliveryService deliveries;
    private final ImageStorage images;
    private final JobBacklogMonitor monitor;

    public BackgroundJobs(MailDeliveryService deliveries, ImageStorage images, JobBacklogMonitor monitor) {
        this.deliveries = deliveries;
        this.images = images;
        this.monitor = monitor;
    }

    @Bean("mailTaskScheduler")
    public ThreadPoolTaskScheduler mailTaskScheduler(
            @org.springframework.beans.factory.annotation.Value("${app.jobs.mail-pool-size:1}") int poolSize) {
        return scheduler("mail-job-", poolSize);
    }

    /** Test and programmatic callers retain a deterministic single-thread default. */
    public ThreadPoolTaskScheduler mailTaskScheduler() { return scheduler("mail-job-", 1); }

    @Bean("maintenanceTaskScheduler")
    public ThreadPoolTaskScheduler maintenanceTaskScheduler(
            @org.springframework.beans.factory.annotation.Value("${app.jobs.maintenance-pool-size:1}") int poolSize) {
        return scheduler("maintenance-job-", poolSize);
    }

    /** Test and programmatic callers retain a deterministic single-thread default. */
    public ThreadPoolTaskScheduler maintenanceTaskScheduler() { return scheduler("maintenance-job-", 1); }

    private ThreadPoolTaskScheduler scheduler(String prefix, int configuredPoolSize) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, configuredPoolSize));
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(25);
        return scheduler;
    }

    @Scheduled(scheduler = "mailTaskScheduler", fixedDelayString = "${app.mail.poll-delay-ms:5000}", initialDelay = 5000)
    public void retryMail() { deliveries.processDue(); }

    @Scheduled(scheduler = "maintenanceTaskScheduler", fixedDelayString = "${app.images.cleanup-delay-ms:3600000}", initialDelay = 60000)
    public void cleanImages() { images.cleanupUnused(); }

    @Scheduled(scheduler = "maintenanceTaskScheduler", fixedDelayString = "${app.jobs.monitor-delay-ms:3600000}", initialDelay = 30000)
    public void reportBacklog() { monitor.logBacklog(); }
}
