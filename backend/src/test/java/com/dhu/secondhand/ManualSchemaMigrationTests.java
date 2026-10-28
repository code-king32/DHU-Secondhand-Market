package com.dhu.secondhand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key","spring.mail.username=test@example.com", "spring.mail.test-connection=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="schema.update.enabled", matches="true")
class ManualSchemaMigrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @MockitoBean private JavaMailSender mailSender;

    @Test
    void appliesAdditiveMigrationsToProjectDatabase() throws Exception {
        assertEquals("dhu_secondhand", jdbc.queryForObject("SELECT DATABASE()", String.class));
        Integer roleColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='role'
                """, Integer.class);
        if (roleColumns == 0) apply("03_user_role.sql");
        apply("04_marketplace.sql");
        apply("05_community.sql");
        apply("06_marketplace_visibility.sql");
        Integer versionColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='session_version'
                """, Integer.class);
        if (versionColumns == 0) apply("07_session_version.sql");
        var tables = Set.copyOf(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class));
        assertTrue(tables.containsAll(Set.of("app_user", "registration_email_code", "market_goods", "market_trade",
                "community_review", "community_report", "community_appeal", "community_audit",
                "community_user_audit", "community_favorite", "community_wanted")));
        assertEquals("USER", jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='role'
                """, String.class));
        System.out.println("项目数据库增量结构验收通过，共核对11张业务表。");
    }

    private void apply(String name) throws Exception {
        Path path = Path.of("..", "sql", name).toAbsolutePath().normalize();
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        assertFalse(sql.matches("(?is).*\\b(DROP\\s+(TABLE|DATABASE|SCHEMA)|TRUNCATE|DELETE)\\b.*"), "迁移必须保持现有数据");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new FileSystemResource(path), StandardCharsets.UTF_8));
        }
    }
}
