package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailCodeCipher;
import com.dhu.secondhand.identity.service.MailDeliveryService;
import com.dhu.secondhand.identity.service.MailService;
import com.dhu.secondhand.marketplace.ImageStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// 只使用本地连接凭据；所有 DDL/DML 均在本测试创建的随机库内执行，邮件始终使用替身。
@EnabledIfSystemProperty(named = "reliability.mysql.enabled", matches = "true")
class ManualReliabilityMySqlTests {
    @TempDir Path directory;

    @Test
    void runtimeSchemaMailRetryAndImageCleanupWorkOnMysql() throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application-local.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String schema = "dhu_runtime_test_" + UUID.randomUUID().toString().replace("-", "");
        boolean created = false;
        try (var connection = DriverManager.getConnection(value(properties, "spring.datasource.url"),
                value(properties, "spring.datasource.username"), value(properties, "spring.datasource.password"))) {
            String originalCatalog = connection.getCatalog();
            try {
                try (var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + schema); }
                created = true;
                connection.setCatalog(schema);
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("sql/runtime-schema.sql"), StandardCharsets.UTF_8));
                // 脚本可重复执行，并且不要求既有业务表先创建。
                ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("sql/runtime-schema.sql"), StandardCharsets.UTF_8));
                var source = new SingleConnectionDataSource(connection, true);
                var jdbc = new JdbcTemplate(source);
                var manager = new DataSourceTransactionManager(source);
                assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
                assertEquals(1, jdbc.queryForObject("""
                        SELECT _utf8mb4'stúdent@mail.dhu.edu.cn' COLLATE utf8mb4_0900_ai_ci
                             = _utf8mb4'student@mail.dhu.edu.cn' COLLATE utf8mb4_0900_ai_ci
                        """, Integer.class));
                jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,status INT,email_verified INT)");
                jdbc.update("INSERT INTO app_user VALUES(1,0,1)");
                jdbc.execute("CREATE TABLE registration_email_code(email VARCHAR(254) PRIMARY KEY,code_hash VARCHAR(100),consumed_at DATETIME)");
                var clock = mock(Clock.class);
                var mail = mock(MailService.class);
                var now = Instant.parse("2026-10-28T04:00:00Z");
                when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
                when(clock.instant()).thenReturn(now);
                String id = UUID.randomUUID().toString().replace("-", "");
                String hash = new BCryptPasswordEncoder().encode("123456");
                var cipher = new MailCodeCipher(directory.resolve("mail.key").toString());
                LocalDateTime time = LocalDateTime.now(clock);
                jdbc.update("INSERT INTO registration_email_code VALUES(?,?,NULL)", "test@mail.dhu.edu.cn", hash);
                jdbc.update("""
                        INSERT INTO app_mail_delivery(id,email,code_hash,code_cipher,status,next_attempt_at,expires_at,created_at)
                        VALUES(?,?,?,?,'PENDING',?,?,?)
                        """, id, "test@mail.dhu.edu.cn", hash, cipher.encrypt("123456", id), time, time.plusMinutes(5), time);
                doThrow(new MailSendException("simulated SMTP delay")).doNothing().when(mail).sendVerificationCode(anyString(), anyString());
                var deliveries = new MailDeliveryService(jdbc, manager, mail, cipher, clock, 3, 15);
                assertEquals("PENDING", deliveries.attempt(id).status());
                when(clock.instant()).thenReturn(now.plusSeconds(15));
                deliveries.processDue();
                assertEquals("SENT", deliveries.status(id).status());
                verify(mail, times(2)).sendVerificationCode("test@mail.dhu.edu.cn", "123456");

                var images = new ImageStorage(directory.resolve("images").toString(), jdbc, manager, clock, 2, 1024, 24);
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
                String url = images.store(1, new MockMultipartFile("file", "a.png", "image/png", bytes.toByteArray()));
                assertTrue(Files.isRegularFile(images.find(url.substring("/api/images/".length()))));
                when(clock.instant()).thenReturn(now.plusSeconds(25 * 3600));
                images.cleanupUnused();
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image", Integer.class));
                try (var files = Files.list(directory.resolve("images"))) { assertEquals(0, files.count()); }
                System.out.println("MySQL runtime schema, same-code retry and image cleanup passed in an isolated temporary database.");
            } finally {
                connection.setCatalog(originalCatalog);
                if (created && schema.matches("dhu_runtime_test_[0-9a-f]{32}")) {
                    try (var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + schema); }
                }
            }
        }
    }

    private String value(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            value = System.getenv(value.substring(2, value.length() - 1));
        }
        if (value == null) throw new IllegalStateException("Missing local property: " + name);
        return value;
    }
}
