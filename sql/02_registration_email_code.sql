USE dhu_secondhand;

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
    UNIQUE KEY uk_registration_email_code_email (email),
    CONSTRAINT chk_registration_email_code_attempts
        CHECK (failed_attempts >= 0)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='注册邮箱验证码';
