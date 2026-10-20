package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "mail.test.enabled", matches = "true")
class ManualMailSendTests {

    @Autowired
    private MailService mailService;

    @Value("${app.mail.test-recipient}")
    private String to;

    @Test
    void sendTestMail() {
        mailService.sendVerificationCode(to, "123456");
    }
}