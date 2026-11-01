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
    public ThreadPoolTaskScheduler mailTaskScheduler() { return scheduler("mail-job-"); }

    @Bean("maintenanceTaskScheduler")
    public ThreadPoolTaskScheduler maintenanceTaskScheduler() { return scheduler("maintenance-job-"); }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }

    @Scheduled(scheduler = "mailTaskScheduler", fixedDelayString = "${app.mail.poll-delay-ms:5000}", initialDelay = 5000)
    public void retryMail() { deliveries.processDue(); }

    @Scheduled(scheduler = "maintenanceTaskScheduler", fixedDelayString = "${app.images.cleanup-delay-ms:3600000}", initialDelay = 60000)
    public void cleanImages() { images.cleanupUnused(); }

    @Scheduled(scheduler = "maintenanceTaskScheduler", fixedDelayString = "${app.jobs.monitor-delay-ms:3600000}", initialDelay = 30000)
    public void reportBacklog() { monitor.logBacklog(); }
}
