package com.dhu.secondhand.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class IdentityConfiguration {

    @Bean
    public Clock registrationClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
