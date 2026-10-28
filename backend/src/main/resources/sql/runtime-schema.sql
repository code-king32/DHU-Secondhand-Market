-- 仅增加本轮功能的独立表，不修改现有业务表或数据；兼容 MySQL 与测试 H2。
CREATE TABLE IF NOT EXISTS app_mail_delivery (
    id VARCHAR(32) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    code_hash VARCHAR(100) NOT NULL,
    code_cipher VARCHAR(256),
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL,
    lease_until TIMESTAMP,
    lease_token VARCHAR(32),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_mail_due (status, next_attempt_at),
    INDEX idx_mail_email (email)
);
CREATE TABLE IF NOT EXISTS app_uploaded_image (
    name VARCHAR(40) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    byte_size BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    unused_since TIMESTAMP,
    status VARCHAR(16) NOT NULL,
    INDEX idx_image_owner (owner_id),
    INDEX idx_image_cleanup (status, unused_since)
);
CREATE TABLE IF NOT EXISTS app_goods_image (
    goods_id BIGINT NOT NULL,
    image_name VARCHAR(40) NOT NULL,
    PRIMARY KEY (goods_id, image_name),
    INDEX idx_goods_image_name (image_name)
);
