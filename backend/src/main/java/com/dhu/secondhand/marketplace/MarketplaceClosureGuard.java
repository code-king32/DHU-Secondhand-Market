package com.dhu.secondhand.marketplace;

import com.dhu.secondhand.account.service.AccountClosureGuard;
import com.dhu.secondhand.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MarketplaceClosureGuard implements AccountClosureGuard {
    private final JdbcTemplate jdbc;

    public MarketplaceClosureGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void checkCanClose(long userId) {
        var active = jdbc.queryForList("""
                SELECT id FROM market_trade
                WHERE (seller_id=? OR buyer_id=?) AND status='ACCEPTED' FOR UPDATE
                """, Long.class, userId, userId);
        if (!active.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "有进行中的交易，暂不能注销");
        }
    }

    @Override
    public void onClosed(long userId) {
        jdbc.update("""
                UPDATE market_goods SET status='OFF_SHELF',updated_at=CURRENT_TIMESTAMP
                WHERE seller_id=? AND status IN ('ON_SALE','OFF_SHELF')
                """, userId);
        jdbc.update("""
                UPDATE market_trade SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP
                WHERE (seller_id=? OR buyer_id=?) AND status='PENDING'
                """, userId, userId);
    }
}
