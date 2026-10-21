USE dhu_secondhand;

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
    UNIQUE KEY uk_app_user_email (email),
    CONSTRAINT chk_app_user_email_verified CHECK (email_verified IN (0, 1)),
    CONSTRAINT chk_app_user_status CHECK (status IN (0, 1, 2))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户表';
