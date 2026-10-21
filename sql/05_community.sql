USE dhu_secondhand;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易评价';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='举报';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='举报申诉';

CREATE TABLE IF NOT EXISTS community_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail VARCHAR(1500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_report FOREIGN KEY (report_id) REFERENCES community_report(id),
    INDEX idx_audit_report (report_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='举报申诉处理审计';

CREATE TABLE IF NOT EXISTS community_user_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    new_status INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_audit_status CHECK (new_status IN (0,1)),
    INDEX idx_user_audit_target (target_user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号状态管理审计';

CREATE TABLE IF NOT EXISTS community_favorite (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    goods_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favorite_user_goods UNIQUE (user_id, goods_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_favorite_goods FOREIGN KEY (goods_id) REFERENCES market_goods(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品收藏';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='求购';
