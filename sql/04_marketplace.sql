USE dhu_secondhand;

CREATE TABLE IF NOT EXISTS market_goods (
    id BIGINT NOT NULL AUTO_INCREMENT,
    seller_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(5000) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    category VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    image_urls TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_market_goods_search (status, category, id),
    KEY idx_market_goods_seller (seller_id, id),
    CONSTRAINT fk_market_goods_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
    CONSTRAINT chk_market_goods_price CHECK (price >= 0 AND price <= 1000000),
    CONSTRAINT chk_market_goods_status CHECK (status IN ('ON_SALE','TRADING','SOLD','OFF_SHELF','HIDDEN')),
    CONSTRAINT chk_market_goods_category CHECK (category IN ('TEXTBOOK','ELECTRONICS','DAILY','CLOTHING','OTHER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='二手商品';

CREATE TABLE IF NOT EXISTS market_trade (
    id BIGINT NOT NULL AUTO_INCREMENT,
    goods_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    agreed_price DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    buyer_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    seller_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_market_trade_goods (goods_id, status),
    KEY idx_market_trade_buyer (buyer_id, id),
    KEY idx_market_trade_seller (seller_id, id),
    CONSTRAINT fk_market_trade_goods FOREIGN KEY (goods_id) REFERENCES market_goods(id),
    CONSTRAINT fk_market_trade_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
    CONSTRAINT fk_market_trade_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
    CONSTRAINT chk_market_trade_parties CHECK (seller_id <> buyer_id),
    CONSTRAINT chk_market_trade_status CHECK (status IN ('PENDING','ACCEPTED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='线下交易申请';
