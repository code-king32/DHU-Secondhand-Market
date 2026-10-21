package com.dhu.secondhand;

import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.marketplace.MarketplaceClosureGuard;
import com.dhu.secondhand.marketplace.MarketplaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.mail.username=test@example.com", "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:marketplace_test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
@Sql("/sql/marketplace-schema.sql")
class MarketplaceTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MarketplaceService service;
    @Autowired MarketplaceClosureGuard closure;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean JavaMailSender mailSender;

    @Test
    void publishesSearchesEditsAndShelvesOwnGoods() throws Exception {
        long id = createGoods(1, "100%线性代数", "TEXTBOOK", "20.00");
        createGoods(1, "100其他", "OTHER", "10.00");
        mvc.perform(get("/api/goods").param("keyword", "100%").param("category", "TEXTBOOK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(put("/api/goods/{id}", id).session(session(1)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(goods("新版教材", "TEXTBOOK", "30.00"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(30.0));
        action(1, "/api/goods/" + id + "/shelf", Map.of("onSale", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFF_SHELF"));
        mvc.perform(get("/api/goods/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/goods/{id}/management", id).session(session(1))).andExpect(status().isOk());
        action(1, "/api/goods/" + id + "/shelf", Map.of("onSale", true)).andExpect(status().isOk());
        mvc.perform(get("/api/goods/mine").session(session(1))).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void rejectsUnauthorizedInvalidAndCrossOwnerMutations() throws Exception {
        mvc.perform(post("/api/goods").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(goods("教材", "TEXTBOOK", "5.00")))).andExpect(status().isUnauthorized());
        action(5, "/api/goods", goods("教材", "TEXTBOOK", "5.00")).andExpect(status().isForbidden());
        long id = createGoods(1, "教材", "TEXTBOOK", "5.00");
        mvc.perform(put("/api/goods/{id}", id).session(session(2)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(goods("教材", "TEXTBOOK", "5.00")))).andExpect(status().isForbidden());
        action(1, "/api/goods", goods("教材", "TEXTBOOK", "-1.00")).andExpect(status().isBadRequest());
        action(1, "/api/goods", goods("教材", "UNKNOWN", "1.00")).andExpect(status().isBadRequest());
        action(1, "/api/goods", Map.of("title", "教材", "description", "描述", "price", "2.00",
                "imageUrls", List.of("javascript:alert(1)"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/goods").param("size", "101")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnicodeWhitespaceOnlyTitleAndDescription() throws Exception {
        action(1, "/api/goods", goods("\u2003", "TEXTBOOK", "2.00")).andExpect(status().isBadRequest());
        action(1, "/api/goods", Map.of("title", "教材", "description", "\u2003", "price", "2.00"))
                .andExpect(status().isBadRequest());
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        mvc.perform(put("/api/goods/{id}", goods).session(session(1)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(goods("\u2003", "TEXTBOOK", "2.00"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void multipleBuyersApplyAndBothPartiesCompleteExactlyOneTrade() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long first = apply(2, goods);
        long second = apply(3, goods);
        action(2, "/api/trades", Map.of("goodsId", goods)).andExpect(status().isConflict());
        action(1, "/api/trades", Map.of("goodsId", goods)).andExpect(status().isBadRequest());
        action(2, "/api/trades/" + first + "/accept", Map.of()).andExpect(status().isForbidden());
        action(1, "/api/trades/" + first + "/accept", Map.of()).andExpect(status().isOk());
        action(1, "/api/trades/" + second + "/accept", Map.of()).andExpect(status().isConflict());
        action(2, "/api/trades/" + first + "/confirm", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.buyerConfirmed").value(true));
        action(1, "/api/trades/" + first + "/confirm", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/goods/{id}", goods)).andExpect(jsonPath("$.status").value("SOLD"));
        mvc.perform(get("/api/trades/{id}", second).session(session(3))).andExpect(jsonPath("$.status").value("CANCELLED"));
        action(1, "/api/trades/" + first + "/confirm", Map.of()).andExpect(status().isOk());
        action(2, "/api/trades/" + first + "/cancel", Map.of()).andExpect(status().isConflict());
        mvc.perform(get("/api/trades/{id}", first).session(session(4))).andExpect(status().isForbidden());
    }

    @Test
    void cancellationRestoresAvailabilityAndPriceRemainsApplicationSnapshot() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long first = apply(2, goods);
        mvc.perform(put("/api/goods/{id}", goods).session(session(1)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(goods("教材", "TEXTBOOK", "30.00")))).andExpect(status().isOk());
        long second = apply(3, goods);
        action(1, "/api/trades/" + first + "/accept", Map.of()).andExpect(jsonPath("$.agreedPrice").value(20.0));
        action(1, "/api/goods/" + goods + "/shelf", Map.of("onSale", false)).andExpect(status().isConflict());
        assertThrows(ApiException.class, () -> closure.checkCanClose(2));
        action(2, "/api/trades/" + first + "/cancel", Map.of()).andExpect(status().isOk());
        assertDoesNotThrow(() -> closure.checkCanClose(2));
        action(1, "/api/trades/" + second + "/accept", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.agreedPrice").value(30.0));
    }

    @Test
    void concurrentAcceptanceSelectsOnlyOneBuyer() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long first = apply(2, goods);
        long second = apply(3, goods);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> acceptAfterStart(first, start));
            var b = executor.submit(() -> acceptAfterStart(second, start));
            start.countDown();
            List<Integer> result = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, result.stream().filter(code -> code == 200).count());
            assertEquals(1, result.stream().filter(code -> code == 409).count());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM market_trade WHERE status='ACCEPTED'", Integer.class));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void concurrentApplicationsFromSameBuyerCreateOnlyOnePendingTrade() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> applyAfterStart(goods, start));
            var second = executor.submit(() -> applyAfterStart(goods, start));
            start.countDown();
            List<Integer> result = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, result.stream().filter(code -> code == 201).count());
            assertEquals(1, result.stream().filter(code -> code == 409).count());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM market_trade WHERE goods_id=? AND buyer_id=2 AND status='PENDING'",
                    Integer.class, goods));
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private int applyAfterStart(long goods, CountDownLatch start) throws Exception {
        start.await();
        try { service.apply(2, goods); return 201; }
        catch (ApiException exception) {
            if (exception.getStatus() != HttpStatus.CONFLICT) throw exception;
            return 409;
        }
    }

    @Test
    void simultaneousBuyerAndSellerConfirmationKeepsBothFlags() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long trade = apply(2, goods);
        service.accept(1, trade);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var buyer = executor.submit(() -> { start.await(); return service.confirm(2, trade); });
            var seller = executor.submit(() -> { start.await(); return service.confirm(1, trade); });
            start.countDown(); buyer.get(15, TimeUnit.SECONDS); seller.get(15, TimeUnit.SECONDS);
            var result = service.trade(2, trade);
            assertEquals("COMPLETED", result.status());
            assertTrue(result.buyerConfirmed()); assertTrue(result.sellerConfirmed());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void refusesAcceptingAnApplicationFromClosedAccount() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long trade = apply(2, goods);
        jdbc.update("UPDATE app_user SET status=2 WHERE id=2");
        action(1, "/api/trades/" + trade + "/accept", Map.of()).andExpect(status().isConflict());
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, goods));
        action(1, "/api/trades/" + trade + "/cancel", Map.of()).andExpect(status().isOk());
    }

    @Test
    void refusesTradingWithInactiveSellerAndUnverifiedBuyer() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        action(6, "/api/trades", Map.of("goodsId", goods)).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET status=2 WHERE id=1");
        action(2, "/api/trades", Map.of("goodsId", goods)).andExpect(status().isConflict());
    }

    @Test
    void accountClosureRetiresListingsAndPendingApplications() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long trade = apply(2, goods);
        closure.checkCanClose(1);
        jdbc.update("UPDATE app_user SET status=2 WHERE id=1");
        closure.onClosed(1);
        assertEquals("OFF_SHELF", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, goods));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM market_trade WHERE id=?", String.class, trade));
        mvc.perform(get("/api/goods")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void ownerCannotUndoModeratorHiddenState() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        jdbc.update("UPDATE market_goods SET status='HIDDEN' WHERE id=?", goods);
        mvc.perform(get("/api/goods/{id}", goods)).andExpect(status().isNotFound());
        action(1, "/api/goods/" + goods + "/shelf", Map.of("onSale", true)).andExpect(status().isConflict());
        mvc.perform(put("/api/goods/{id}", goods).session(session(1)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(goods("新版教材", "TEXTBOOK", "30.00"))))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptingTradeCannotPassAnAccountClosureAlreadyHoldingItsLock() throws Exception {
        long goods = createGoods(1, "教材", "TEXTBOOK", "20.00");
        long trade = apply(2, goods);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        try {
            var close = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM app_user WHERE id=2 FOR UPDATE", Long.class);
                locked.countDown();
                try {
                    if (!allowClose.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test close timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                closure.checkCanClose(2);
                jdbc.update("UPDATE app_user SET status=2 WHERE id=2");
                closure.onClosed(2);
                return true;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var accept = executor.submit(() -> {
                try { service.accept(1, trade); return 200; }
                catch (ApiException exception) { return 409; }
            });
            allowClose.countDown();
            assertTrue(close.get(15, TimeUnit.SECONDS));
            assertEquals(409, accept.get(15, TimeUnit.SECONDS));
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM market_trade WHERE id=?", String.class, trade));
            assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=?", String.class, goods));
        } finally { allowClose.countDown(); executor.shutdownNow(); }
    }

    private int acceptAfterStart(long id, CountDownLatch start) throws Exception {
        start.await();
        try { service.accept(1, id); return 200; }
        catch (ApiException exception) { return 409; }
    }

    private long createGoods(long actor, String title, String category, String price) throws Exception {
        String response = action(actor, "/api/goods", goods(title, category, price)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private long apply(long actor, long goods) throws Exception {
        String response = action(actor, "/api/trades", Map.of("goodsId", goods)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private Map<String, Object> goods(String title, String category, String price) {
        return Map.of("title", title, "description", "八成新，校园当面交易", "price", price,
                "category", category, "imageUrls", List.of("https://example.invalid/book.png"));
    }

    private ResultActions action(long actor, String path, Object body) throws Exception {
        return mvc.perform(post(path).session(session(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private MockHttpSession session(long userId) {
        MockHttpSession session = new MockHttpSession(); session.setAttribute("userId", userId); return session;
    }
}
