package com.dhu.secondhand;

import com.dhu.secondhand.account.entity.User;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.identity.exception.RegistrationCodeException;
import com.dhu.secondhand.identity.service.RegistrationCodeService;
import com.dhu.secondhand.identity.service.VerificationCodeHasher;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key",
        "spring.mail.username=test@example.com",
        "spring.mail.test-connection=false"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "db.test.enabled", matches = "true")
class ManualDatabaseConnectionTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RegistrationCodeService registrationCodeService;

    @Autowired
    private VerificationCodeHasher hasher;

    private String registrationTestEmail;

    @MockitoBean
    private JavaMailSender mailSender;

    @Test
    void connectsToProjectDatabaseAndReadsUserTable() {
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class);

        assertEquals("dhu_secondhand", databaseName);

        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user", Long.class);

        assertNotNull(userCount);

        System.out.println("连接数据库：" + databaseName);
        System.out.println("用户表记录数：" + userCount);
    }

    @Test
    @Transactional
    void insertsAndReadsUserThroughMapper() {
        assertEquals("dhu_secondhand", jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class));

        User user = new User();
        user.setEmail("mapper-test-" + UUID.randomUUID() + "@example.invalid");
        user.setPasswordHash("test-placeholder");
        user.setNickname("映射测试");

        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId());

        User saved = userMapper.selectById(user.getId());

        assertNotNull(saved);
        assertEquals(user.getEmail(), saved.getEmail());
        assertEquals(user.getPasswordHash(), saved.getPasswordHash());
        assertEquals(user.getNickname(), saved.getNickname());
        assertEquals(Integer.valueOf(0), saved.getEmailVerified());
        assertEquals(Integer.valueOf(0), saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void storesRegistrationCodeAndEnforcesCooldownInMysql() {
        assertEquals("dhu_secondhand", jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class));
        registrationTestEmail = "test-" + UUID.randomUUID() + "@mail.dhu.edu.cn";

        registrationCodeService.send(registrationTestEmail);
        String hash = jdbcTemplate.queryForObject(
                "SELECT code_hash FROM registration_email_code WHERE email=?",
                String.class, registrationTestEmail);
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertNotNull(message.getValue().getText());
        var code = Pattern.compile("[0-9]{6}").matcher(message.getValue().getText());
        assertTrue(code.find());
        assertTrue(hasher.matches(code.group(), hash));

        RegistrationCodeException exception = assertThrows(RegistrationCodeException.class,
                () -> registrationCodeService.send(registrationTestEmail));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
    }

    @AfterEach
    void removesOnlyThisTestsCommittedMailAndCodeRecords() {
        if (registrationTestEmail != null) {
            jdbcTemplate.update("DELETE FROM app_mail_delivery WHERE email=?", registrationTestEmail);
            jdbcTemplate.update("DELETE FROM registration_email_code WHERE email=?", registrationTestEmail);
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM registration_email_code WHERE email=?",
                    Integer.class, registrationTestEmail));
        }
    }
}
