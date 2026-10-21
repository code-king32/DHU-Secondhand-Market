DROP TABLE IF EXISTS market_trade;
DROP TABLE IF EXISTS market_goods;
DROP TABLE IF EXISTS app_user;
CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    email_verified TINYINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 0,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    session_version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE market_goods (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seller_id BIGINT NOT NULL REFERENCES app_user(id),
    title VARCHAR(100) NOT NULL,
    description VARCHAR(5000) NOT NULL,
    price DECIMAL(10,2) NOT NULL CHECK (price >= 0 AND price <= 1000000),
    category VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    image_urls TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE market_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    goods_id BIGINT NOT NULL REFERENCES market_goods(id),
    seller_id BIGINT NOT NULL REFERENCES app_user(id),
    buyer_id BIGINT NOT NULL REFERENCES app_user(id),
    agreed_price DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    buyer_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    seller_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO app_user(id,email,password_hash,nickname,email_verified,status) VALUES
(1,'seller@mail.dhu.edu.cn','test-placeholder','卖家',1,0),
(2,'buyer@mail.dhu.edu.cn','test-placeholder','买家',1,0),
(3,'buyer2@mail.dhu.edu.cn','test-placeholder','另一买家',1,0),
(4,'other@mail.dhu.edu.cn','test-placeholder','其他人',1,0),
(5,'disabled@mail.dhu.edu.cn','test-placeholder','禁用用户',1,1),
(6,'unverified@mail.dhu.edu.cn','test-placeholder','未认证用户',0,0);
