package com.dhu.secondhand;

import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.marketplace.MarketDtos.GoodsWriteRequest;
import com.dhu.secondhand.marketplace.MarketplaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.mail.username=test@example.com",
        "spring.mail.test-connection=false"
})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "db.test.enabled", matches = "true")
class ManualMarketplaceTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MarketplaceService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private JavaMailSender mailSender;

    private final Map<Long, String> fixtureUsers = new LinkedHashMap<>();
    private long sellerId;
    private long buyerId;
    private long goodsId;

    @BeforeEach
    void createUniqueFixtures() {
        assertEquals("dhu_secondhand", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String marker = "market-regression-" + UUID.randomUUID();
        sellerId = createUser(marker + "-seller@example.invalid");
        buyerId = createUser(marker + "-buyer@example.invalid");
        goodsId = service.create(sellerId, new GoodsWriteRequest("并发申请回归测试", "仅用于本次自动验收",
                new BigDecimal("12.50"), "OTHER", List.of())).id();
    }

    @AfterEach
    void removeOnlyThisTestsFixtures() {
        // 只删除本次创建并记录的编号；不清表、不匹配其他用户的数据。
        if (goodsId != 0) {
            jdbc.update("DELETE FROM market_trade WHERE goods_id=? AND seller_id=?", goodsId, sellerId);
            jdbc.update("DELETE FROM market_goods WHERE id=? AND seller_id=?", goodsId, sellerId);
        }
        fixtureUsers.forEach((id, email) -> jdbc.update("DELETE FROM app_user WHERE id=? AND email=?", id, email));
    }

    @Test
    void repeatableReadSnapshotsCannotProduceDuplicatePendingApplications() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch snapshotsReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> applyFromOldSnapshot(snapshotsReady, start));
            var second = executor.submit(() -> applyFromOldSnapshot(snapshotsReady, start));
            assertTrue(snapshotsReady.await(10, TimeUnit.SECONDS), "两个事务必须先建立旧快照");
            start.countDown();
            List<Integer> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 201).count());
            assertEquals(1, results.stream().filter(code -> code == 409).count());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM market_trade
                    WHERE goods_id=? AND buyer_id=? AND status IN ('PENDING','ACCEPTED')
                    """, Integer.class, goodsId, buyerId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "测试线程须结束后才能清理数据");
        }
    }

    private int applyFromOldSnapshot(CountDownLatch snapshotsReady, CountDownLatch start) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            return transaction.execute(status -> {
                assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM market_trade WHERE goods_id=?",
                        Integer.class, goodsId));
                snapshotsReady.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试启动等待超时");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                service.apply(buyerId, goodsId);
                return 201;
            });
        } catch (ApiException exception) {
            if (exception.getStatus() != HttpStatus.CONFLICT) throw exception;
            return 409;
        }
    }

    private long createUser(String email) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO app_user(email,password_hash,nickname,email_verified,status)
                    VALUES (?,'test-placeholder','并发测试用户',1,0)
                    """, new String[]{"id"});
            statement.setString(1, email);
            return statement;
        }, key);
        long id = key.getKey().longValue();
        fixtureUsers.put(id, email);
        return id;
    }
}
