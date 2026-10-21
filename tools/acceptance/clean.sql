-- 仅清理带有验收标记的独立测试库，保留表结构与会话代数。
DELIMITER $$
USE dhu_secondhand_acceptance$$
DROP PROCEDURE IF EXISTS acceptance_clean$$
CREATE PROCEDURE acceptance_clean()
BEGIN
    DECLARE current_marker VARCHAR(40) DEFAULT NULL;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;
    IF DATABASE() <> 'dhu_secondhand_acceptance' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Acceptance database required';
    END IF;
    START TRANSACTION;
    SELECT marker INTO current_marker FROM _acceptance_meta WHERE id=1 FOR UPDATE;
    IF current_marker IS NULL OR current_marker <> 'dhu-acceptance-v1' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Acceptance marker missing or invalid';
    END IF;
    UPDATE _acceptance_meta SET generation=GREATEST(generation,
        COALESCE((SELECT MAX(session_version) FROM app_user),0)) WHERE id=1;
    DELETE FROM community_audit;
    DELETE FROM community_appeal;
    DELETE FROM community_report;
    DELETE FROM community_user_audit;
    DELETE FROM community_review;
    DELETE FROM community_favorite;
    DELETE FROM community_wanted;
    DELETE FROM market_trade;
    DELETE FROM market_goods;
    DELETE FROM registration_email_code;
    DELETE FROM app_user;
    COMMIT;
END$$
CALL acceptance_clean()$$
DROP PROCEDURE acceptance_clean$$
DELIMITER ;
