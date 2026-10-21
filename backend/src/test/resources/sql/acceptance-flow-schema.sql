DROP ALL OBJECTS;

CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    email VARCHAR(254) NOT NULL COMMENT '校园邮箱，同时作为登录账号',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    nickname VARCHAR(50) NOT NULL COMMENT '展示昵称',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '邮箱验证状态：0未验证，1已验证',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '账号状态：0正常，1禁用，2注销',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT chk_app_user_email_verified CHECK (email_verified IN (0, 1)),
    CONSTRAINT chk_app_user_status CHECK (status IN (0, 1, 2))
);

ALTER TABLE app_user ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE app_user ADD COLUMN session_version INT NOT NULL DEFAULT 0;

CREATE TABLE registration_email_code (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    email VARCHAR(254) NOT NULL COMMENT '待注册的校园邮箱',
    code_hash VARCHAR(255) NOT NULL COMMENT '验证码哈希',
    expires_at DATETIME NOT NULL COMMENT '验证码过期时间',
    last_sent_at DATETIME NOT NULL COMMENT '最近发送时间',
    failed_attempts INT NOT NULL DEFAULT 0 COMMENT '验证码错误次数',
    consumed_at DATETIME NULL COMMENT '成功使用时间，未使用时为空',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_registration_email_code_email UNIQUE (email),
    CONSTRAINT chk_registration_email_code_attempts
        CHECK (failed_attempts >= 0)
);

CREATE TABLE market_goods (
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
    CONSTRAINT fk_market_goods_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
    CONSTRAINT chk_market_goods_price CHECK (price >= 0 AND price <= 1000000),
    CONSTRAINT chk_market_goods_status CHECK (status IN ('ON_SALE','TRADING','SOLD','OFF_SHELF','HIDDEN')),
    CONSTRAINT chk_market_goods_category CHECK (category IN ('TEXTBOOK','ELECTRONICS','DAILY','CLOTHING','OTHER'))
);

CREATE TABLE market_trade (
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
    CONSTRAINT fk_market_trade_goods FOREIGN KEY (goods_id) REFERENCES market_goods(id),
    CONSTRAINT fk_market_trade_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
    CONSTRAINT fk_market_trade_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
    CONSTRAINT chk_market_trade_parties CHECK (seller_id <> buyer_id),
    CONSTRAINT chk_market_trade_status CHECK (status IN ('PENDING','ACCEPTED','COMPLETED','CANCELLED'))
);

CREATE TABLE IF NOT EXISTS community_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trade_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    reviewee_id BIGINT NOT NULL,
    rating INT NOT NULL,
    review_text VARCHAR(1000) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_review_trade_reviewer UNIQUE (trade_id, reviewer_id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT fk_review_trade FOREIGN KEY (trade_id) REFERENCES market_trade(id),
    CONSTRAINT fk_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user(id),
    CONSTRAINT fk_review_reviewee FOREIGN KEY (reviewee_id) REFERENCES app_user(id),
    INDEX idx_review_reviewee (reviewee_id,id)
);

CREATE TABLE IF NOT EXISTS community_report (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    reporter_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolution VARCHAR(1000) NULL,
    resolver_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_target CHECK (target_type IN ('GOODS','USER','REVIEW')),
    CONSTRAINT chk_report_status CHECK (status IN ('OPEN','RESOLVED','REJECTED')),
    CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id) REFERENCES app_user(id),
    INDEX idx_report_owner (reporter_id,id),
    INDEX idx_report_status (status,id)
);

CREATE TABLE IF NOT EXISTS community_appeal (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    appellant_id BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolution VARCHAR(1000) NULL,
    resolver_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_appeal_report UNIQUE (report_id),
    CONSTRAINT chk_appeal_status CHECK (status IN ('OPEN','UPHELD','REJECTED')),
    CONSTRAINT fk_appeal_report FOREIGN KEY (report_id) REFERENCES community_report(id),
    INDEX idx_appeal_status (status,id)
);

CREATE TABLE IF NOT EXISTS community_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail VARCHAR(1500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_report FOREIGN KEY (report_id) REFERENCES community_report(id),
    INDEX idx_audit_report (report_id,id)
);

CREATE TABLE IF NOT EXISTS community_user_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    new_status INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_audit_status CHECK (new_status IN (0,1)),
    INDEX idx_user_audit_target (target_user_id,id)
);

CREATE TABLE IF NOT EXISTS community_favorite (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favorite_user_goods UNIQUE (user_id, goods_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_favorite_goods FOREIGN KEY (goods_id) REFERENCES market_goods(id)
);

CREATE TABLE IF NOT EXISTS community_wanted (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    max_price DECIMAL(12,2) NOT NULL,
    category VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_wanted_price CHECK (max_price>=0),
    CONSTRAINT chk_wanted_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT chk_wanted_category CHECK (category IN ('TEXTBOOK','ELECTRONICS','DAILY','CLOTHING','OTHER')),
    CONSTRAINT fk_wanted_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_wanted_status_category (status,category,id)
);
