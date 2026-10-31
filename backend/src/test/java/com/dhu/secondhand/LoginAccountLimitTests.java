package com.dhu.secondhand;

import com.dhu.secondhand.account.entity.User;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.common.*;
import com.dhu.secondhand.identity.controller.AuthController;
import com.dhu.secondhand.identity.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginAccountLimitTests {
    @Test
    void databaseEquivalentEmailsShareTheAccountBudgetAcrossIps() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T04:00:00Z"), ZoneOffset.UTC);
        var protection = new AbuseProtection(clock, true, 30, 5, 600, 20, 100, 10, 1000);
        var users = mock(UserMapper.class);
        User user = new User();
        user.setId(1L);
        user.setEmail("student@mail.dhu.edu.cn");
        user.setPasswordHash("stored-hash");
        // MySQL 的 ai_ci 排序规则会让这些不同的输入命中同一个用户。
        when(users.selectOne(any())).thenReturn(user);
        var passwords = mock(PasswordService.class);
        var service = new AccountAuthService(users, null, passwords, null, clock, protection);
        var controller = new AuthController(service, null, users, protection, new ClientIpResolver(""));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        var emails = List.of("student", "stúdent", "stùdent", "stûdent", "stüdent", "stūdent");
        for (int index = 0; index < emails.size(); index++) {
            String ip = "192.0.2." + (index + 1);
            var result = mvc.perform(post("/api/auth/login").with(request -> {
                        request.setRemoteAddr(ip); return request;
                    }).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + emails.get(index) + "@mail.dhu.edu.cn\",\"password\":\"wrong-password\"}"));
            if (index < 5) result.andExpect(status().isUnauthorized());
            else result.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
        verify(passwords, times(5)).matches("wrong-password", "stored-hash");
    }
}
