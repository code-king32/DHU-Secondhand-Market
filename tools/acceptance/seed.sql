-- 固定演示账号，统一演示密码 DhuDemo123!；只能用于本地验收库。
DELIMITER $$
USE dhu_secondhand_acceptance$$
DROP PROCEDURE IF EXISTS acceptance_seed$$
CREATE PROCEDURE acceptance_seed()
BEGIN
    DECLARE current_marker VARCHAR(40) DEFAULT NULL;
    DECLARE next_generation INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;
    IF DATABASE() <> 'dhu_secondhand_acceptance' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Acceptance database required';
    END IF;
    START TRANSACTION;
    SELECT marker, generation+1 INTO current_marker, next_generation
        FROM _acceptance_meta WHERE id=1 FOR UPDATE;
    IF current_marker IS NULL OR current_marker <> 'dhu-acceptance-v1' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Acceptance marker missing or invalid';
    END IF;
    IF EXISTS(SELECT 1 FROM app_user) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Acceptance database must be empty; use Reset';
    END IF;
    UPDATE _acceptance_meta SET generation=next_generation WHERE id=1;
    INSERT INTO app_user(id,email,password_hash,nickname,email_verified,status,role,session_version) VALUES
        (1,'demo.seller@mail.dhu.edu.cn','$2a$10$BSmkqPfKs2UxYZoaODlVf.t6bg8hAmc1vF/b/PEnFNFYNyBkRMGpq','验收卖家',1,0,'USER',next_generation),
        (2,'demo.buyer@mail.dhu.edu.cn','$2a$10$BSmkqPfKs2UxYZoaODlVf.t6bg8hAmc1vF/b/PEnFNFYNyBkRMGpq','验收买家',1,0,'USER',next_generation),
        (3,'demo.admin@mail.dhu.edu.cn','$2a$10$BSmkqPfKs2UxYZoaODlVf.t6bg8hAmc1vF/b/PEnFNFYNyBkRMGpq','验收管理员',1,0,'ADMIN',next_generation),
        (4,'demo.other@mail.dhu.edu.cn','$2a$10$BSmkqPfKs2UxYZoaODlVf.t6bg8hAmc1vF/b/PEnFNFYNyBkRMGpq','验收其他用户',1,0,'USER',next_generation);
    INSERT INTO market_goods(id,seller_id,title,description,price,category,image_urls,status) VALUES
        (1,1,'高等数学教材（在售样例）','可用于检索、收藏及申请交易',20,'TEXTBOOK','[]','ON_SALE'),
        (2,1,'台灯（已下架样例）','卖家可在管理页重新上架',15,'DAILY','[]','OFF_SHELF'),
        (3,1,'键盘（交易中样例）','对应交易1，等待双方确认',60,'ELECTRONICS','[]','TRADING'),
        (4,1,'英语教材（已售出样例）','对应已完成交易2及双向评价',18,'TEXTBOOK','[]','SOLD'),
        (5,1,'水杯（管理隐藏样例）','卖家不能自行重新上架',10,'DAILY','[]','HIDDEN');
    INSERT INTO market_trade(id,goods_id,seller_id,buyer_id,agreed_price,status,buyer_confirmed,seller_confirmed) VALUES
        (1,3,1,2,60,'ACCEPTED',FALSE,FALSE),
        (2,4,1,2,18,'COMPLETED',TRUE,TRUE),
        (3,1,1,4,20,'PENDING',FALSE,FALSE);
    INSERT INTO community_review(id,trade_id,reviewer_id,reviewee_id,rating,review_text) VALUES
        (1,2,2,1,5,'教材整洁，按约交易'),(2,2,1,2,4,'沟通顺利');
    INSERT INTO community_report(id,reporter_id,target_type,target_id,reason) VALUES
        (1,4,'USER',1,'验收待处理举报样例');
    INSERT INTO community_favorite(id,user_id,goods_id) VALUES(1,2,1);
    INSERT INTO community_wanted(id,user_id,title,description,max_price,category) VALUES
        (1,2,'求购线性代数教材','无缺页即可，校内面交',30,'TEXTBOOK');
    COMMIT;
END$$
CALL acceptance_seed()$$
DROP PROCEDURE acceptance_seed$$
DELIMITER ;
