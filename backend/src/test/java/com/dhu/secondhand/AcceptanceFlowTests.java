package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key",
        "spring.mail.username=test@example.com",
        "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:acceptance_flow;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@Sql({"/sql/acceptance-flow-schema.sql", "/sql/runtime-schema.sql"})
class AcceptanceFlowTests {

    private static final String PASSWORD = "Acceptance123!";
    private static final String ORIGIN = "http://localhost";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MailService mailService;

    private final Map<String, String> deliveredCodes = new ConcurrentHashMap<>();

    @BeforeEach
    void captureMailWithoutSendingIt() {
        deliveredCodes.clear();
        doAnswer(invocation -> {
            deliveredCodes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mailService).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void rejectsCrossSiteAndFormRequestsBeforeSendingMail() throws Exception {
        mvc.perform(post("/api/auth/registration-codes")
                        .header("Origin", "https://untrusted.example.invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "student@mail.dhu.edu.cn"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/registration-codes")
                        .header("Origin", ORIGIN).header("Sec-Fetch-Site", "cross-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "student@mail.dhu.edu.cn"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/registration-codes")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "student@mail.dhu.edu.cn"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(mailService);
    }

    @Test
    void sessionRotationLogoutAndAccountClosureRespectAuthenticationBoundaries() throws Exception {
        String email = "session@mail.dhu.edu.cn";
        register(email, "会话测试");
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        MockHttpSession anonymous = new MockHttpSession();
        String anonymousId = anonymous.getId();
        MvcResult login = send(post("/api/auth/login").session(anonymous),
                Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession first = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(first);
        assertNotEquals(anonymousId, first.getId());
        assertSafeUser(read(login));
        mvc.perform(get("/api/auth/me").session(first)).andExpect(status().isOk());

        Client second = login(email);
        send(delete("/api/account/me").session(first), Map.of("password", "wrong-password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        mvc.perform(get("/api/auth/me").session(first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(second.session)).andExpect(status().isOk());

        send(delete("/api/account/me").session(first), Map.of("password", PASSWORD))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/auth/me").session(second.session)).andExpect(status().is4xxClientError());
        send(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD))
                .andExpect(status().is4xxClientError());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM app_user WHERE email=?", Integer.class, email));

        Client active = registerAndLogin("logout@dhu.edu.cn", "退出测试");
        send(post("/api/auth/logout").session(active.session), Map.of())
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/auth/me").session(active.session)).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCodeAttemptsPersistAndEventuallyLockRegistration() throws Exception {
        String email = "attempts@mail.dhu.edu.cn";
        requestCode(email);
        String correct = deliveredCodes.get(email);
        String wrong = "000000".equals(correct) ? "111111" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            send(post("/api/auth/register"), registration(email, "尝试次数", wrong))
                    .andExpect(status().is4xxClientError());
            assertEquals(attempt, jdbc.queryForObject(
                    "SELECT failed_attempts FROM registration_email_code WHERE email=?", Integer.class, email));
        }
        send(post("/api/auth/register"), registration(email, "尝试次数", correct))
                .andExpect(status().is4xxClientError());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email));
    }

    @Test
    void missingExpiredAndConsumedCodesCannotCreateAnAccount() throws Exception {
        String absent = "absent@mail.dhu.edu.cn";
        send(post("/api/auth/register"), registration(absent, "缺失验证码", "123456"))
                .andExpect(status().is4xxClientError());

        String expired = "expired@mail.dhu.edu.cn";
        requestCode(expired);
        jdbc.update("UPDATE registration_email_code SET expires_at=? WHERE email=?",
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1), expired);
        send(post("/api/auth/register"), registration(expired, "过期验证码", deliveredCodes.get(expired)))
                .andExpect(status().is4xxClientError());

        String consumed = "consumed@mail.dhu.edu.cn";
        requestCode(consumed);
        jdbc.update("UPDATE registration_email_code SET consumed_at=? WHERE email=?",
                LocalDateTime.now(ZoneId.of("Asia/Shanghai")), consumed);
        send(post("/api/auth/register"), registration(consumed, "已用验证码", deliveredCodes.get(consumed)))
                .andExpect(status().is4xxClientError());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class));
    }

    @Test
    void concurrentRegistrationConsumesOneCodeAndCreatesOneAccount() throws Exception {
        String email = "concurrent@mail.dhu.edu.cn";
        requestCode(email);
        Map<String, Object> body = registration(email, "并发注册", deliveredCodes.get(email));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> concurrentRegister(body, ready, start));
            var second = executor.submit(() -> concurrentRegister(body, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 201).count());
            assertEquals(1, statuses.stream().filter(status -> status >= 400 && status < 500).count());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email));
            assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM registration_email_code WHERE email=?",
                    LocalDateTime.class, email));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void roleInjectionIsIgnoredAndPasswordsRespectUtf8ByteLimit() throws Exception {
        String email = "injection@mail.dhu.edu.cn";
        requestCode(email);
        var oversized = new LinkedHashMap<>(registration(email, "密码边界", deliveredCodes.get(email)));
        oversized.put("password", "汉".repeat(25));
        send(post("/api/auth/register"), oversized).andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM registration_email_code WHERE email=?",
                LocalDateTime.class, email));

        var injection = new LinkedHashMap<>(registration(email, "权限边界", deliveredCodes.get(email)));
        injection.put("role", "ADMIN");
        injection.put("status", 0);
        injection.put("id", 999);
        send(post("/api/auth/register"), injection).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM app_user WHERE email=?", String.class, email));

        String boundary = "boundary@dhu.edu.cn";
        requestCode(boundary);
        var allowed = new LinkedHashMap<>(registration(boundary, "编码边界", deliveredCodes.get(boundary)));
        allowed.put("password", "汉".repeat(24));
        send(post("/api/auth/register"), allowed).andExpect(status().isCreated());
        send(post("/api/auth/login"), Map.of("email", boundary, "password", "汉".repeat(24)))
                .andExpect(status().isOk());
    }

    @Test
    void profileValidationAndPublicResponsesKeepAccountDataPrivate() throws Exception {
        Client client = registerAndLogin("profile@mail.dhu.edu.cn", "原昵称");
        send(patch("/api/account/me").session(client.session), Map.of("nickname", "   "))
                .andExpect(status().isBadRequest());
        send(patch("/api/account/me").session(client.session), Map.of("nickname", "\u2003"))
                .andExpect(status().isBadRequest());
        for (String blank : List.of("\u00a0", "\u200b", "\u2060")) {
            send(patch("/api/account/me").session(client.session), Map.of("nickname", blank))
                    .andExpect(status().isBadRequest());
        }
        send(patch("/api/account/me").session(client.session), Map.of("nickname", " 新昵称 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("新昵称"));
        JsonNode profile = read(mvc.perform(get("/api/users/{id}", client.id))
                .andExpect(status().isOk()).andReturn());
        assertEquals("新昵称", profile.path("nickname").asText());
        assertFalse(profile.has("email"));
        assertFalse(profile.has("passwordHash"));
        assertFalse(profile.has("role"));
    }

    @Test
    void invisibleNicknameCannotRegisterOrConsumeAValidCode() throws Exception {
        String email = "invisible@mail.dhu.edu.cn";
        requestCode(email);
        for (String blank : List.of("\u00a0", "\u200b", "\u2060")) {
            send(post("/api/auth/register"), registration(email, blank, deliveredCodes.get(email)))
                    .andExpect(status().isBadRequest());
        }
        send(post("/api/auth/register"), registration(email, "有效昵称", deliveredCodes.get(email)))
                .andExpect(status().isCreated());
    }

    @Test
    void closingAccountBlocksAcceptedTradesThenClosesPendingBusiness() throws Exception {
        Client seller = registerAndLogin("closing-seller@mail.dhu.edu.cn", "待注销卖家");
        Client buyer = registerAndLogin("closing-buyer@dhu.edu.cn", "买家");
        long goodsId = createGoods(seller, "注销边界商品");
        long accepted = createTrade(buyer, goodsId);
        send(post("/api/trades/{id}/accept", accepted).session(seller.session), Map.of())
                .andExpect(status().isOk());
        send(delete("/api/account/me").session(seller.session), Map.of("password", PASSWORD))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM app_user WHERE id=?", Integer.class, seller.id));
        send(post("/api/trades/{id}/cancel", accepted).session(buyer.session), Map.of())
                .andExpect(status().isOk());
        long pending = createTrade(buyer, goodsId);
        send(delete("/api/account/me").session(seller.session), Map.of("password", PASSWORD))
                .andExpect(status().isNoContent());
        assertEquals("OFF_SHELF", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, goodsId));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM market_trade WHERE id=?", String.class, pending));
        mvc.perform(get("/api/goods/{id}", goodsId)).andExpect(status().isNotFound());
    }

    @Test
    void adminRestorationRequiresNewLoginAndNeverRestoresClosedAccounts() throws Exception {
        Client admin = registerAndLogin("restore-admin@dhu.edu.cn", "管理人员");
        Client target = registerAndLogin("restore-target@mail.dhu.edu.cn", "待恢复账号");
        Client ordinary = registerAndLogin("restore-ordinary@mail.dhu.edu.cn", "普通用户");
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", admin.id);

        send(put("/api/admin/users/{id}/status", target.id).session(ordinary.session), Map.of("status", 1))
                .andExpect(status().isForbidden());
        send(put("/api/admin/users/{id}/status", target.id).session(admin.session), Map.of("status", 1))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(target.session)).andExpect(status().is4xxClientError());
        send(put("/api/admin/users/{id}/status", target.id).session(admin.session), Map.of("status", 0))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(target.session)).andExpect(status().isUnauthorized());

        Client restored = login("restore-target@mail.dhu.edu.cn");
        send(delete("/api/account/me").session(restored.session), Map.of("password", PASSWORD))
                .andExpect(status().isNoContent());
        send(put("/api/admin/users/{id}/status", target.id).session(admin.session), Map.of("status", 0))
                .andExpect(status().isConflict());
        send(put("/api/admin/users/{id}/status", admin.id).session(admin.session), Map.of("status", 1))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void apiPathParametersCannotBypassSourceProtection() throws Exception {
        Client client = registerAndLogin("path-guard@mail.dhu.edu.cn", "路径保护");
        mvc.perform(post("/api;ignored/auth/logout").session(client.session)
                        .header("Referer", "https://untrusted.example.invalid/page")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/auth/me").session(client.session)).andExpect(status().isOk());
    }

    @Test
    void disableThenRestoreRevokesSessionsWithoutAnyInterveningUserRequest() throws Exception {
        Client admin = registerAndLogin("version-admin@dhu.edu.cn", "管理人员");
        Client target = registerAndLogin("version-target@mail.dhu.edu.cn", "版本测试");
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", admin.id);
        send(put("/api/admin/users/{id}/status", target.id).session(admin.session), Map.of("status", 1))
                .andExpect(status().isOk());
        send(put("/api/admin/users/{id}/status", target.id).session(admin.session), Map.of("status", 0))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(target.session)).andExpect(status().isUnauthorized());
        Client relogged = login("version-target@mail.dhu.edu.cn");
        mvc.perform(get("/api/auth/me").session(relogged.session)).andExpect(status().isOk());
    }

    @Test
    void sellerCannotUndoAdministratorGoodsModeration() throws Exception {
        Client seller = registerAndLogin("moderated-seller@mail.dhu.edu.cn", "卖家");
        Client reporter = registerAndLogin("moderated-reporter@mail.dhu.edu.cn", "举报人");
        Client admin = registerAndLogin("moderated-admin@dhu.edu.cn", "管理人员");
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", admin.id);
        long goodsId = createGoods(seller, "待治理商品");
        long reportId = read(send(post("/api/reports").session(reporter.session), Map.of(
                "targetType", "GOODS", "targetId", goodsId, "reason", "验收管理员下架权限"))
                .andExpect(status().is2xxSuccessful()).andReturn()).path("id").asLong();
        send(put("/api/admin/reports/{id}/resolution", reportId).session(admin.session),
                Map.of("decision", "RESOLVED", "note", "商品违反发布规则", "action", "HIDE_GOODS"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/goods/{id}", goodsId)).andExpect(status().isNotFound());
        send(post("/api/goods/{id}/shelf", goodsId).session(seller.session), Map.of("onSale", true))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/goods")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void campusTradingAndGovernanceCompleteThroughHttpSessions() throws Exception {
        Client seller = registerAndLogin("seller@mail.dhu.edu.cn", "卖家");
        Client buyer = registerAndLogin("buyer@dhu.edu.cn", "买家");
        Client other = registerAndLogin("other@mail.dhu.edu.cn", "其他买家");
        Client admin = registerAndLogin("admin@dhu.edu.cn", "管理人员");
        jdbc.update("UPDATE app_user SET role='ADMIN' WHERE id=?", admin.id);

        long goodsId = read(send(post("/api/goods").session(seller.session), Map.of(
                "title", "高等数学教材", "description", "课程二手教材", "price", "25.50",
                "category", "TEXTBOOK", "imageUrls", List.of("https://images.example.invalid/book.jpg")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        assertTrue(goodsId > 0);
        mvc.perform(get("/api/goods").param("category", "TEXTBOOK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(goodsId));
        send(put("/api/favorites/{id}", goodsId).session(buyer.session), Map.of())
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/favorites").session(buyer.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));

        long wantedId = read(send(post("/api/wanted").session(buyer.session), Map.of(
                "title", "求购线性代数", "description", "教材可有笔记", "maxPrice", "30.00", "category", "TEXTBOOK"))
                .andExpect(status().is2xxSuccessful()).andReturn()).path("id").asLong();
        send(put("/api/wanted/{id}/close", wantedId).session(other.session), Map.of())
                .andExpect(status().isForbidden());
        send(put("/api/wanted/{id}/close", wantedId).session(buyer.session), Map.of())
                .andExpect(status().isOk());

        send(post("/api/trades").session(seller.session), Map.of("goodsId", goodsId))
                .andExpect(status().is4xxClientError());
        long tradeId = createTrade(buyer, goodsId);
        Map<String, Object> editedGoods = Map.of("title", "高等数学教材", "description", "课程二手教材",
                "price", "30.00", "category", "TEXTBOOK", "imageUrls", List.of());
        send(put("/api/goods/{id}", goodsId).session(other.session), editedGoods)
                .andExpect(status().isForbidden());
        send(put("/api/goods/{id}", goodsId).session(seller.session), editedGoods)
                .andExpect(status().isOk());
        long otherTradeId = createTrade(other, goodsId);
        mvc.perform(get("/api/trades/{id}", tradeId).session(buyer.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreedPrice").value(25.50));
        mvc.perform(get("/api/trades/{id}", otherTradeId).session(other.session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreedPrice").value(30.00));
        send(post("/api/trades/{id}/accept", tradeId).session(other.session), Map.of())
                .andExpect(status().isForbidden());
        send(post("/api/trades/{id}/accept", tradeId).session(seller.session), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        send(post("/api/trades/{id}/accept", otherTradeId).session(seller.session), Map.of())
                .andExpect(status().isConflict());
        send(post("/api/trades/{id}/confirm", tradeId).session(buyer.session), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        send(post("/api/trades/{id}/confirm", tradeId).session(other.session), Map.of())
                .andExpect(status().isForbidden());
        send(post("/api/trades/{id}/confirm", tradeId).session(seller.session), Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals("SOLD", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, goodsId));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM market_trade WHERE id=?", String.class, otherTradeId));

        send(post("/api/reviews").session(other.session), Map.of("tradeId", tradeId, "rating", 5, "comment", "无权评价"))
                .andExpect(status().isForbidden());
        send(post("/api/reviews").session(buyer.session), Map.of("tradeId", tradeId, "rating", 5, "comment", "教材与描述一致"))
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.revieweeId").value(seller.id));
        send(post("/api/reviews").session(buyer.session), Map.of("tradeId", tradeId, "rating", 5, "comment", "重复评价"))
                .andExpect(status().isConflict());
        send(post("/api/reviews").session(seller.session), Map.of("tradeId", tradeId, "rating", 4, "comment", "按时见面"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/users/{id}/reputation", seller.id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.averageRating").value(5.0));

        long reportId = createReport(buyer, seller.id);
        send(put("/api/admin/reports/{id}/resolution", reportId).session(seller.session),
                Map.of("decision", "REJECTED", "note", "无管理员权限", "action", "NONE"))
                .andExpect(status().isForbidden());
        send(put("/api/admin/reports/{id}/resolution", reportId).session(admin.session),
                Map.of("decision", "REJECTED", "note", "先行驳回", "action", "NONE"))
                .andExpect(status().isOk());
        long appealId = read(send(post("/api/reports/{id}/appeals", reportId).session(buyer.session),
                Map.of("reason", "提交补充说明"))
                .andExpect(status().is2xxSuccessful()).andReturn()).path("id").asLong();
        send(post("/api/reports/{id}/appeals", reportId).session(buyer.session), Map.of("reason", "重复申诉"))
                .andExpect(status().isConflict());
        send(put("/api/admin/appeals/{id}/resolution", appealId).session(admin.session),
                Map.of("decision", "UPHELD", "note", "重新审查举报"))
                .andExpect(status().isOk());
        send(put("/api/admin/reports/{id}/resolution", reportId).session(admin.session),
                Map.of("decision", "RESOLVED", "note", "已完成复核", "action", "NONE"))
                .andExpect(status().isOk());

        long disabledReport = createReport(buyer, other.id);
        send(put("/api/admin/reports/{id}/resolution", disabledReport).session(admin.session),
                Map.of("decision", "RESOLVED", "note", "验收停用权限", "action", "DISABLE_USER"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(other.session)).andExpect(status().is4xxClientError());
        send(delete("/api/favorites/{id}", goodsId).session(buyer.session), Map.of())
                .andExpect(status().isNoContent());
    }

    private long createReport(Client client, long targetId) throws Exception {
        return read(send(post("/api/reports").session(client.session), Map.of(
                "targetType", "USER", "targetId", targetId, "reason", "验收举报处理流程"))
                .andExpect(status().is2xxSuccessful()).andReturn()).path("id").asLong();
    }

    private long createTrade(Client client, long goodsId) throws Exception {
        return read(send(post("/api/trades").session(client.session), Map.of("goodsId", goodsId))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long createGoods(Client client, String title) throws Exception {
        return read(send(post("/api/goods").session(client.session), Map.of(
                "title", title, "description", "验收商品", "price", "25.00",
                "category", "OTHER", "imageUrls", List.of()))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private Client registerAndLogin(String email, String nickname) throws Exception {
        register(email, nickname);
        return login(email);
    }

    private void register(String email, String nickname) throws Exception {
        requestCode(email);
        MvcResult result = send(post("/api/auth/register"), registration(email, nickname, deliveredCodes.get(email)))
                .andExpect(status().isCreated()).andReturn();
        assertSafeUser(read(result));
    }

    private void requestCode(String email) throws Exception {
        send(post("/api/auth/registration-codes"), Map.of("email", email)).andExpect(status().isOk());
        assertNotNull(deliveredCodes.get(email));
    }

    private Client login(String email) throws Exception {
        MvcResult result = send(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()).andReturn();
        JsonNode user = read(result);
        assertSafeUser(user);
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return new Client(user.path("id").asLong(), session);
    }

    private Map<String, Object> registration(String email, String nickname, String code) {
        return Map.of("email", email, "nickname", nickname, "code", code, "password", PASSWORD);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        return mvc.perform(request.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertSafeUser(JsonNode user) {
        assertTrue(user.path("id").asLong() > 0);
        assertFalse(user.has("passwordHash"));
        assertFalse(user.has("password_hash"));
        assertFalse(user.has("password"));
    }

    private int concurrentRegister(Map<String, Object> body, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test start timed out");
        return send(post("/api/auth/register"), body).andReturn().getResponse().getStatus();
    }

    private record Client(long id, MockHttpSession session) {
    }
}
