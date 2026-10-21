USE dhu_secondhand;

ALTER TABLE app_user
    ADD COLUMN session_version INT NOT NULL DEFAULT 0 COMMENT '会话版本，停用或恢复时递增';
