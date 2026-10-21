USE dhu_secondhand;

ALTER TABLE market_goods DROP CHECK chk_market_goods_status;
ALTER TABLE market_goods ADD CONSTRAINT chk_market_goods_status
    CHECK (status IN ('ON_SALE','TRADING','SOLD','OFF_SHELF','HIDDEN'));
