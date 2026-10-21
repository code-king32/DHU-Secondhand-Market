package com.dhu.secondhand;

import com.dhu.secondhand.identity.exception.RegistrationCodeException;
import com.dhu.secondhand.identity.service.MailService;
import com.dhu.secondhand.identity.service.RegistrationCodeService;
import com.dhu.secondhand.identity.service.VerificationCodeHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.mail.username=test@example.com",
        "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:registration_code_test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@Sql("/sql/registration-code-schema.sql")
class RegistrationCodeTests {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final String EMAIL = "student@mail.dhu.edu.cn";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VerificationCodeHasher hasher;
    @Autowired private RegistrationCodeService service;

    @MockitoBean private MailService mailService;
    @MockitoBean private Clock clock;

    @BeforeEach
    void fixTime() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void sendsStudentCodeAndStoresOnlyHash() throws Exception {
        send("STUDENT@MAIL.DHU.EDU.CN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("验证码已发送"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.length()").value(3));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationCode(eq(EMAIL), code.capture());
        assertTrue(code.getValue().matches("[0-9]{6}"));
        String hash = storedHash();
        assertNotEquals(code.getValue(), hash);
        assertTrue(hasher.matches(code.getValue(), hash));
        assertEquals(LocalDateTime.of(2026, 10, 21, 16, 5), jdbc.queryForObject(
                "SELECT expires_at FROM registration_email_code", LocalDateTime.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user", Integer.class));
    }

    @Test
    void acceptsTeacherEmail() throws Exception {
        send("teacher@dhu.edu.cn").andExpect(status().isOk());
        verify(mailService).sendVerificationCode(eq("teacher@dhu.edu.cn"), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "a@gmail.com", "a@evilmail.dhu.edu.cn",
            "a@dhu.edu.cn.evil.test", "a@other.dhu.edu.cn"})
    void rejectsInvalidAndNonCampusEmails(String email) throws Exception {
        send(email).andExpect(status().isBadRequest());
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsMissingEmailAndMalformedJson() throws Exception {
        mvc.perform(post("/api/auth/registration-codes")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/registration-codes")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mailService);
    }

    @Test
    void rejectsRegisteredEmail() throws Exception {
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname) VALUES (?, ?, ?)",
                EMAIL, "test-placeholder", "测试用户");
        send(EMAIL).andExpect(status().isConflict());
        verifyNoInteractions(mailService);
    }

    @Test
    void enforcesCooldownWithoutChangingCurrentCode() throws Exception {
        send(EMAIL).andExpect(status().isOk());
        String firstHash = storedHash();
        send(EMAIL).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        assertEquals(firstHash, storedHash());
        verify(mailService, times(1)).sendVerificationCode(eq(EMAIL), anyString());
    }

    @Test
    void resendsAtCooldownBoundaryAndResetsPreviousState() throws Exception {
        send(EMAIL).andExpect(status().isOk());
        String firstHash = storedHash();
        jdbc.update("UPDATE registration_email_code SET failed_attempts=3, consumed_at=last_sent_at");
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        send(EMAIL).andExpect(status().isOk());
        assertNotEquals(firstHash, storedHash());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM registration_email_code", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT failed_attempts FROM registration_email_code", Integer.class));
        assertNull(jdbc.queryForObject(
                "SELECT consumed_at FROM registration_email_code", LocalDateTime.class));
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(mailService, times(2)).sendVerificationCode(eq(EMAIL), codes.capture());
        assertTrue(hasher.matches(codes.getAllValues().get(1), storedHash()));
    }

    @Test
    void rollsBackNewRecordWhenMailFails() throws Exception {
        doThrow(new MailSendException("test mail failure"))
                .when(mailService).sendVerificationCode(anyString(), anyString());
        send(EMAIL).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("邮件发送失败，请稍后重试"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM registration_email_code", Integer.class));
    }

    @Test
    void preservesPreviousCodeWhenResendFails() throws Exception {
        send(EMAIL).andExpect(status().isOk());
        String firstHash = storedHash();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        doThrow(new MailSendException("test mail failure"))
                .when(mailService).sendVerificationCode(anyString(), anyString());

        send(EMAIL).andExpect(status().isBadGateway());
        assertEquals(firstHash, storedHash());
        assertEquals(LocalDateTime.of(2026, 10, 21, 16, 0), jdbc.queryForObject(
                "SELECT last_sent_at FROM registration_email_code", LocalDateTime.class));
    }

    @Test
    void concurrentRequestsSendOnlyOneCode() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> concurrentSend(ready, start));
            var second = executor.submit(() -> concurrentSend(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 200).count());
            assertEquals(1, results.stream().filter(code -> code == 429).count());
            verify(mailService, times(1)).sendVerificationCode(eq(EMAIL), anyString());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private int concurrentSend(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test start timed out");
        }
        try {
            service.send(EMAIL);
            return 200;
        } catch (RegistrationCodeException exception) {
            return exception.getStatus().value();
        }
    }

    private ResultActions send(String email) throws Exception {
        return mvc.perform(post("/api/auth/registration-codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email))));
    }

    private String storedHash() {
        return jdbc.queryForObject("SELECT code_hash FROM registration_email_code WHERE email=?",
                String.class, EMAIL);
    }
}
