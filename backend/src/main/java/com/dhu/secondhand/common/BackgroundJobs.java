package com.dhu.secondhand.common;

import com.dhu.secondhand.identity.service.MailDeliveryService;
import com.dhu.secondhand.marketplace.ImageStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundJobs {
    private final MailDeliveryService deliveries;
    private final ImageStorage images;

    public BackgroundJobs(MailDeliveryService deliveries, ImageStorage images) {
        this.deliveries = deliveries;
        this.images = images;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-delay-ms:5000}", initialDelay = 5000)
    public void retryMail() { deliveries.processDue(); }

    @Scheduled(fixedDelayString = "${app.images.cleanup-delay-ms:3600000}", initialDelay = 60000)
    public void cleanImages() { images.cleanupUnused(); }
}
