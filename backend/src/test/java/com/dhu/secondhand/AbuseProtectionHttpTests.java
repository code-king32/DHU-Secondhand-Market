package com.dhu.secondhand;

import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.identity.service.AccountAuthService;
import com.dhu.secondhand.identity.service.RegistrationCodeService;
import com.dhu.secondhand.identity.dto.SendRegistrationCodeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=true", "app.abuse.login-ip-limit=2",
        "app.web.trusted-proxies=192.0.2.10",
        "app.abuse.login-account-limit=5", "app.mail.key-file=target/test-mail-code.key",
        "app.abuse.mail-ip-hour-limit=2",
        "spring.mail.username=test@example.com", "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:abuse_http;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class AbuseProtectionHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean AccountAuthService accounts;
    @MockitoBean JavaMailSender mail;
    @MockitoBean RegistrationCodeService registration;

    @Test
    void spoofingForwardedHeadersCannotBypassIpBudgetAnd429IncludesRetryAfter() throws Exception {
        when(accounts.login(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        for (int index = 1; index <= 3; index++) {
            var result = mvc.perform(post("/api/auth/login").with(request -> {
                        request.setRemoteAddr("192.0.2.1"); return request;
                    }).header("X-Forwarded-For", "198.51.100." + index)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"test@mail.dhu.edu.cn\",\"password\":\"wrong-password\"}"));
            if (index < 3) result.andExpect(status().isUnauthorized());
            else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.message").value("操作过于频繁，请稍后重试"));
        }
        verify(accounts, times(2)).login(any());
    }

    @Test
    void mailIpLimitRejectsBeforeCreatingAnotherDelivery() throws Exception {
        when(registration.send(any())).thenReturn(new SendRegistrationCodeResponse("验证码已发送", 300, 60,
                "00000000000000000000000000000000", "SENT"));
        for (int index = 1; index <= 3; index++) {
            var result = mvc.perform(post("/api/auth/registration-codes").with(request -> {
                        request.setRemoteAddr("192.0.2.2"); return request;
                    }).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"test" + index + "@mail.dhu.edu.cn\"}"));
            if (index < 3) result.andExpect(status().isOk());
            else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
        verify(registration, times(2)).send(any());
    }

    @Test
    void trustedProxySeparatesLoginBudgetsButSpoofedPrefixesDoNot() throws Exception {
        when(accounts.login(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        for (int index = 1; index <= 3; index++) {
            mvc.perform(post("/api/auth/login").with(request -> {
                        request.setRemoteAddr("192.0.2.10"); return request;
                    }).header("X-Forwarded-For", "198.51.100." + index)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"proxy" + index + "@mail.dhu.edu.cn\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        for (int index = 1; index <= 3; index++) {
            var result = mvc.perform(post("/api/auth/login").with(request -> {
                        request.setRemoteAddr("192.0.2.10"); return request;
                    }).header("X-Forwarded-For", "203.0.113." + index + ",198.51.100.20")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"spoof" + index + "@mail.dhu.edu.cn\",\"password\":\"wrong-password\"}"));
            if (index < 3) result.andExpect(status().isUnauthorized());
            else result.andExpect(status().isTooManyRequests());
        }
        verify(accounts, times(5)).login(any());
    }

    @Test
    void trustedProxySeparatesMailBudgets() throws Exception {
        when(registration.send(any())).thenReturn(new SendRegistrationCodeResponse("验证码已发送", 300, 60,
                "00000000000000000000000000000000", "SENT"));
        for (int index = 1; index <= 3; index++) {
            mvc.perform(post("/api/auth/registration-codes").with(request -> {
                        request.setRemoteAddr("192.0.2.10"); return request;
                    }).header("X-Forwarded-For", "198.51.100." + (index + 30))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"mailproxy" + index + "@mail.dhu.edu.cn\"}"))
                    .andExpect(status().isOk());
        }
        verify(registration, times(3)).send(any());
    }
}
