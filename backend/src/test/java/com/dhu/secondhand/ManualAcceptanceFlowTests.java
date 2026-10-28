package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key","spring.mail.username=test@example.com", "spring.mail.test-connection=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "db.test.enabled", matches = "true")
class ManualAcceptanceFlowTests {
    private static final String PASSWORD = "MysqlAcceptance123!";
    private final String marker = "acceptance-" + UUID.randomUUID();
    private final List<String> fixtureEmails = new ArrayList<>();
    private final Map<String, String> deliveredCodes = new ConcurrentHashMap<>();

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private MailService mailService;

    @BeforeEach
    void requireProjectDatabaseAndCaptureMail() {
        assertEquals("dhu_secondhand", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='app_user'
                AND column_name IN ('role','session_version')
                """, Integer.class), "先完成账号角色及会话版本迁移");
        doAnswer(call -> {
            deliveredCodes.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(mailService).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void mysqlCommitsTradingGovernanceAndAccountClosureAcrossHttpRequests() throws Exception {
        Client seller = registerAndLogin("seller");
        Client buyer = registerAndLogin("buyer");
        Client other = registerAndLogin("other");
        Client admin = registerAndLogin("admin");
        // 只提升本次新建的账号，结束时按随机邮箱及其编号清理。
        assertEquals(1, jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=? AND email=?",
                admin.id(), admin.email()));

        Map<String, Object> goodsBody = Map.of("title", marker, "description", "真实HTTP与数据库闭环测试",
                "price", "25.50", "category", "TEXTBOOK", "imageUrls", List.of());
        long goodsId = request(seller.http(), "POST", "/api/goods", goodsBody, 201).path("id").asLong();
        assertEquals(1, request(buyer.http(), "GET", "/api/goods?keyword=" + marker, null, 200).path("total").asInt());
        request(buyer.http(), "PUT", "/api/goods/" + goodsId, goodsBody, 403);
        request(buyer.http(), "PUT", "/api/favorites/" + goodsId, Map.of(), 200);

        long tradeId = apply(buyer, goodsId);
        long competingTrade = apply(other, goodsId);
        assertEquals("ACCEPTED", request(seller.http(), "POST", "/api/trades/" + tradeId + "/accept",
                Map.of(), 200).path("status").asText());
        request(seller.http(), "DELETE", "/api/account/me", Map.of("password", PASSWORD), 409);
        assertEquals("ACCEPTED", request(buyer.http(), "POST", "/api/trades/" + tradeId + "/confirm",
                Map.of(), 200).path("status").asText());
        assertEquals("COMPLETED", request(seller.http(), "POST", "/api/trades/" + tradeId + "/confirm",
                Map.of(), 200).path("status").asText());
        assertEquals("SOLD", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, goodsId));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM market_trade WHERE id=?",
                String.class, competingTrade));
        assertEquals(seller.id(), request(buyer.http(), "POST", "/api/reviews", Map.of(
                "tradeId", tradeId, "rating", 5, "comment", "教材符合描述"), 201).path("revieweeId").asLong());
        request(seller.http(), "POST", "/api/reviews", Map.of(
                "tradeId", tradeId, "rating", 4, "comment", "按时交易"), 201);
        assertEquals(5.0, request(buyer.http(), "GET", "/api/users/" + seller.id() + "/reputation",
                null, 200).path("averageRating").asDouble());

        long reportId = request(buyer.http(), "POST", "/api/reports", Map.of(
                "targetType", "USER", "targetId", other.id(), "reason", "验收举报处理"), 201).path("id").asLong();
        request(admin.http(), "PUT", "/api/admin/reports/" + reportId + "/resolution", Map.of(
                "decision", "REJECTED", "note", "等待补充信息", "action", "NONE"), 200);
        long appealId = request(buyer.http(), "POST", "/api/reports/" + reportId + "/appeals",
                Map.of("reason", "补充情况说明"), 201).path("id").asLong();
        request(admin.http(), "PUT", "/api/admin/appeals/" + appealId + "/resolution",
                Map.of("decision", "UPHELD", "note", "重新核查"), 200);
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM community_report WHERE id=?",
                String.class, reportId));
        request(admin.http(), "PUT", "/api/admin/reports/" + reportId + "/resolution", Map.of(
                "decision", "RESOLVED", "note", "核查后停用", "action", "DISABLE_USER"), 200);
        request(admin.http(), "PUT", "/api/admin/users/" + other.id() + "/status", Map.of("status", 0), 200);
        request(other.http(), "GET", "/api/auth/me", null, 401);
        assertEquals(2, jdbc.queryForObject("SELECT session_version FROM app_user WHERE id=?", Integer.class, other.id()));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM community_audit WHERE report_id=?",
                Integer.class, reportId));

        long hiddenGoods = request(seller.http(), "POST", "/api/goods", goodsBody, 201).path("id").asLong();
        long goodsReport = request(buyer.http(), "POST", "/api/reports", Map.of(
                "targetType", "GOODS", "targetId", hiddenGoods, "reason", "验收隐藏商品"), 201).path("id").asLong();
        request(admin.http(), "PUT", "/api/admin/reports/" + goodsReport + "/resolution", Map.of(
                "decision", "RESOLVED", "note", "隐藏测试商品", "action", "HIDE_GOODS"), 200);
        assertEquals("HIDDEN", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, hiddenGoods));
        request(seller.http(), "POST", "/api/goods/" + hiddenGoods + "/shelf", Map.of("onSale", true), 409);
        request(buyer.http(), "GET", "/api/goods/" + hiddenGoods, null, 404);

        long wantedId = request(buyer.http(), "POST", "/api/wanted", Map.of(
                "title", marker, "description", "求购测试", "maxPrice", "30.00", "category", "TEXTBOOK"), 201)
                .path("id").asLong();
        assertEquals("CLOSED", request(buyer.http(), "PUT", "/api/wanted/" + wantedId + "/close",
                Map.of(), 200).path("status").asText());
        request(buyer.http(), "DELETE", "/api/favorites/" + goodsId, Map.of(), 204);
        request(seller.http(), "DELETE", "/api/account/me", Map.of("password", PASSWORD), 204);
        request(buyer.http(), "GET", "/api/users/" + seller.id(), null, 404);
        request(buyer.http(), "POST", "/api/auth/logout", Map.of(), 204);
        request(buyer.http(), "GET", "/api/auth/me", null, 401);
    }

    @AfterEach
    void cleanOnlyThisRunsFixturesAndVerifyRemoval() {
        if (fixtureEmails.isEmpty()) return;
        assertEquals("dhu_secondhand", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String emails = String.join(",", java.util.Collections.nCopies(fixtureEmails.size(), "?"));
        List<Long> ids = jdbc.queryForList("SELECT id FROM app_user WHERE email IN (" + emails + ")",
                Long.class, fixtureEmails.toArray());
        transactions.executeWithoutResult(transaction -> {
            if (!ids.isEmpty()) {
                String slots = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
                Object[] args = ids.toArray();
                String ownReports = "SELECT id FROM community_report WHERE reporter_id IN (" + slots + ")";
                jdbc.update("DELETE FROM community_audit WHERE report_id IN (" + ownReports + ")", args);
                jdbc.update("DELETE FROM community_appeal WHERE report_id IN (" + ownReports + ")", args);
                jdbc.update("DELETE FROM community_report WHERE reporter_id IN (" + slots + ")", args);
                jdbc.update("DELETE FROM community_user_audit WHERE actor_id IN (" + slots + ")", args);
                jdbc.update("DELETE FROM community_review WHERE reviewer_id IN (" + slots + ")", args);
                jdbc.update("DELETE FROM community_favorite WHERE user_id IN (" + slots + ")", args);
                jdbc.update("DELETE FROM community_wanted WHERE user_id IN (" + slots + ")", args);
                // 若存在外部用户新增的关联，外键应阻止清理，而不是删除外部数据。
                List<Long> parties = new ArrayList<>(ids);
                parties.addAll(ids);
                jdbc.update("DELETE FROM market_trade WHERE seller_id IN (" + slots + ") AND buyer_id IN (" + slots + ")",
                        parties.toArray());
                jdbc.update("DELETE FROM market_goods WHERE seller_id IN (" + slots + ")", args);
                jdbc.update("DELETE FROM app_user WHERE id IN (" + slots + ")", args);
            }
            jdbc.update("DELETE FROM registration_email_code WHERE email IN (" + emails + ")", fixtureEmails.toArray());
        });
        for (String email : fixtureEmails) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM registration_email_code WHERE email=?", Integer.class, email));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM market_goods WHERE title=?", Integer.class, marker));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM community_wanted WHERE title=?", Integer.class, marker));
    }

    private Client registerAndLogin(String label) throws Exception {
        String email = marker + "-" + label + "@mail.dhu.edu.cn";
        fixtureEmails.add(email);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        request(http, "POST", "/api/auth/registration-codes", Map.of("email", email), 200);
        assertNotNull(deliveredCodes.get(email));
        JsonNode user = request(http, "POST", "/api/auth/register", Map.of("email", email,
                "code", deliveredCodes.get(email), "password", PASSWORD, "nickname", label), 201);
        assertFalse(user.has("passwordHash"));
        assertEquals("USER", user.path("role").asText());
        request(http, "GET", "/api/auth/me", null, 401);
        request(http, "POST", "/api/auth/login", Map.of("email", email, "password", PASSWORD), 200);
        assertEquals(user.path("id").asLong(), request(http, "GET", "/api/auth/me", null, 200).path("id").asLong());
        return new Client(user.path("id").asLong(), email, http);
    }

    private long apply(Client buyer, long goodsId) throws Exception {
        return request(buyer.http(), "POST", "/api/trades", Map.of("goodsId", goodsId), 201).path("id").asLong();
    }

    private JsonNode request(HttpClient client, String method, String path, Map<String, ?> body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port).timeout(Duration.ofSeconds(15));
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        }
        var response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), method + " " + path + " 返回: " + response.body());
        return response.body().isBlank() ? NullNode.getInstance() : json.readTree(response.body());
    }

    private record Client(long id, String email, HttpClient http) { }
}
