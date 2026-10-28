package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key","spring.mail.username=test@example.com", "spring.mail.test-connection=false"})
@ActiveProfiles("local")
@ContextConfiguration(initializers = ManualAcceptanceKitTests.DatabaseInitializer.class)
@EnabledIfSystemProperty(named = "acceptance.kit.test.enabled", matches = "true")
class ManualAcceptanceKitTests {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path KIT = ROOT.resolve("tools/acceptance");
    private static final String DATABASE = "dhu_secondhand_acceptance";
    private static String sourceUrl;
    private static String targetUrl;
    private static String dbUser;
    private static String dbPassword;
    private static long sourceUsers;

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MailService mailService;

    public static class DatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            sourceUrl = env.getRequiredProperty("spring.datasource.url");
            dbUser = env.getRequiredProperty("spring.datasource.username");
            dbPassword = env.getRequiredProperty("spring.datasource.password");
            if (!sourceUrl.matches("jdbc:mysql://[^/]+/dhu_secondhand(?:\\?.*)?")) {
                throw new IllegalStateException("Use the normal local dhu_secondhand connection for this opt-in test");
            }
            targetUrl = sourceUrl.replaceFirst("/dhu_secondhand(?=\\?|$)", "/" + DATABASE);
            try (Connection source = DriverManager.getConnection(sourceUrl, dbUser, dbPassword)) {
                sourceUsers = userCount(source);
                boolean exists;
                try (var query = source.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                    query.setString(1, DATABASE);
                    try (var rows = query.executeQuery()) { rows.next(); exists = rows.getInt(1) != 0; }
                }
                if (!exists) {
                    try (var statement = source.createStatement()) {
                        statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                    }
                    try (Connection target = connect()) {
                        for (String name : new String[]{"01_user.sql", "02_registration_email_code.sql", "03_user_role.sql",
                                "04_marketplace.sql", "05_community.sql", "06_marketplace_visibility.sql", "07_session_version.sql"}) {
                            String sql = Files.readString(ROOT.resolve("sql").resolve(name));
                            assertTrue(sql.contains("USE dhu_secondhand;"));
                            execute(target, sql.replace("USE dhu_secondhand;", "USE " + DATABASE + ";"), ";");
                        }
                        execute(target, "CREATE TABLE _acceptance_meta(id INT PRIMARY KEY,marker VARCHAR(40) NOT NULL,generation INT NOT NULL);"
                                + "INSERT INTO _acceptance_meta VALUES(1,'dhu-acceptance-v1',0);", ";");
                    }
                } else {
                    // 清理脚本先校验标记，拒绝接管同名但来源不明的库。
                    script("clean.sql");
                }
                script("seed.sql");
            } catch (Exception error) {
                throw new IllegalStateException("Acceptance fixture preparation failed", error);
            }
            TestPropertyValues.of("spring.datasource.url=" + targetUrl).applyTo(context.getEnvironment());
        }
    }

    @Test
    void runsExportedCollectionAndGuardsFixtureReset() throws Exception {
        assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(DISTINCT status) FROM market_goods", Integer.class));
        var output = Files.createTempFile("dhu-acceptance-run-", ".txt");
        try {
            for (int round = 1; round <= 2; round++) {
                var process = new ProcessBuilder("node", KIT.resolve("run-collection.mjs").toString(),
                        "--base-url", "http://localhost:" + port).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    fail("Acceptance collection timed out");
                }
                String result = Files.readString(output);
                System.out.println("Collection round " + round + ":\n" + result);
                assertEquals(0, process.exitValue(), result);
                assertTrue(result.contains("Completed: 54 requests"));
            }
        } finally {
            Files.deleteIfExists(output);
        }
        assertThrows(Exception.class, () -> script("seed.sql"), "不能覆盖已有样例或测试结果");
        jdbc.update("UPDATE _acceptance_meta SET marker='invalid' WHERE id=1");
        try {
            assertThrows(Exception.class, () -> script("clean.sql"));
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class));
        } finally {
            jdbc.update("UPDATE _acceptance_meta SET marker='dhu-acceptance-v1' WHERE id=1");
        }
        // 此账号在集合中经历禁用和恢复；旧版本仍须在数据重置后失效。
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        assertEquals(200, login(client).statusCode());
        int previousVersion = jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=4", Integer.class);
        try {
            script("clean.sql");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class));
            script("seed.sql");
            assertTrue(jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=4", Integer.class) > previousVersion);
            var me = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")).GET().build();
            assertEquals(401, client.send(me, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, login(client).statusCode());
        } finally {
            // 留下可供组长直接使用的基线样例，所有写入只发生在独立验收库。
            script("clean.sql");
            script("seed.sql");
        }
        try (var source = DriverManager.getConnection(sourceUrl, dbUser, dbPassword)) {
            assertEquals(sourceUsers, userCount(source), "日常开发库用户数不应变化");
        }
    }

    private HttpResponse<String> login(HttpClient client) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"demo.other@mail.dhu.edu.cn\",\"password\":\"DhuDemo123!\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long userCount(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM app_user")) {
            rows.next(); return rows.getLong(1);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(targetUrl, dbUser, dbPassword);
    }

    private static void script(String file) throws Exception {
        String sql = Files.readString(KIT.resolve(file)).replaceAll("(?m)^DELIMITER.*\\R?", "");
        try (var connection = connect()) { execute(connection, sql, "$$"); }
    }

    private static void execute(Connection connection, String sql, String separator) {
        var resource = new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        ScriptUtils.executeSqlScript(connection, resource, false, false, "--", separator, "/*", "*/");
    }
}
