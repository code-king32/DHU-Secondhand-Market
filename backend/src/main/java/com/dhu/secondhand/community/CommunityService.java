package com.dhu.secondhand.community;

import com.dhu.secondhand.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class CommunityService {

    private static final Set<String> CATEGORIES = Set.of("TEXTBOOK", "ELECTRONICS", "DAILY", "CLOTHING", "OTHER");
    private final JdbcTemplate jdbc;

    public CommunityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ReviewView review(long actor, long tradeId, int rating, String comment) {
        lockActors(actor, null, false);
        var trades = jdbc.query("SELECT buyer_id,seller_id,status FROM market_trade WHERE id=? FOR UPDATE",
                (rs, row) -> new TradeParties(rs.getLong("buyer_id"), rs.getLong("seller_id"), rs.getString("status")), tradeId);
        if (trades.isEmpty()) throw missing("交易不存在");
        TradeParties trade = trades.get(0);
        if (actor != trade.buyerId() && actor != trade.sellerId()) throw forbidden("只有交易双方可以评价");
        if (!"COMPLETED".equals(trade.status())) throw conflict("交易完成后才能评价");
        if (count("SELECT COUNT(*) FROM community_review WHERE trade_id=? AND reviewer_id=?", tradeId, actor) > 0) {
            throw conflict("你已评价过这笔交易");
        }
        long target = actor == trade.buyerId() ? trade.sellerId() : trade.buyerId();
        long id = insert("INSERT INTO community_review(trade_id,reviewer_id,reviewee_id,rating,review_text) VALUES(?,?,?,?,?)",
                tradeId, actor, target, rating, text(comment, 1000));
        return reviewById(id);
    }

    public Page<ReviewView> reviews(long userId, int page, int size) {
        ensureUserExists(userId);
        int offset = offset(page, size);
        return new Page<>(jdbc.query("SELECT * FROM community_review WHERE reviewee_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                this::mapReview, userId, size, offset), page, size,
                count("SELECT COUNT(*) FROM community_review WHERE reviewee_id=?", userId));
    }

    public ReputationView reputation(long userId) {
        ensureUserExists(userId);
        long total = count("SELECT COUNT(*) FROM community_review WHERE reviewee_id=?", userId);
        Double average = jdbc.queryForObject("SELECT COALESCE(AVG(rating),0) FROM community_review WHERE reviewee_id=?",
                Double.class, userId);
        return new ReputationView(userId, total,
                BigDecimal.valueOf(average == null ? 0 : average).setScale(2, RoundingMode.HALF_UP));
    }

    @Transactional
    public ReportView report(long actor, String targetType, long targetId, String reason) {
        lockActors(actor, "USER".equals(targetType) ? targetId : null, false);
        long owner = targetOwner(targetType, targetId);
        if (owner == actor) throw bad("不能举报自己的内容或账号");
        if (count("SELECT COUNT(*) FROM community_report WHERE reporter_id=? AND target_type=? AND target_id=? AND status='OPEN'",
                actor, targetType, targetId) > 0) throw conflict("该对象已有待处理的举报");
        long id = insert("INSERT INTO community_report(reporter_id,target_type,target_id,reason) VALUES(?,?,?,?)",
                actor, targetType, targetId, text(reason, 1000));
        audit(actor, id, "REPORT_CREATED", text(reason, 1000));
        return reportById(id, false);
    }

    public Page<ReportView> myReports(long actor, int page, int size) {
        int offset = offset(page, size);
        return new Page<>(jdbc.query("SELECT * FROM community_report WHERE reporter_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                this::mapReport, actor, size, offset), page, size,
                count("SELECT COUNT(*) FROM community_report WHERE reporter_id=?", actor));
    }

    public Page<ReportView> reports(String status, int page, int size) {
        int offset = offset(page, size);
        String filter = filter(status, Set.of("OPEN", "RESOLVED", "REJECTED"));
        String where = filter.isEmpty() ? "" : " WHERE status=?";
        Object[] args = filter.isEmpty() ? new Object[]{size, offset} : new Object[]{filter, size, offset};
        return new Page<>(jdbc.query("SELECT * FROM community_report" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                this::mapReport, args), page, size, filter.isEmpty()
                ? count("SELECT COUNT(*) FROM community_report")
                : count("SELECT COUNT(*) FROM community_report WHERE status=?", filter));
    }

    @Transactional
    public ReportView resolveReport(long admin, long reportId, String decision, String note, String action) {
        String operation = action == null ? "NONE" : action;
        ReportView preliminary = reportById(reportId, false);
        lockActors(admin, "DISABLE_USER".equals(operation) && "USER".equals(preliminary.targetType())
                ? preliminary.targetId() : null, true);
        ReportView report = reportById(reportId, true);
        if (!"OPEN".equals(report.status())) throw conflict("举报已处理");
        if (!"RESOLVED".equals(decision) && !"REJECTED".equals(decision)) throw bad("处理结果不正确");
        if (!"NONE".equals(operation) && !"RESOLVED".equals(decision)) throw bad("驳回举报时不能执行处置");
        switch (operation) {
            case "NONE" -> { }
            case "HIDE_GOODS" -> {
                if (!"GOODS".equals(report.targetType())) throw bad("隐藏商品仅适用于商品举报");
                var states = jdbc.queryForList("SELECT status FROM market_goods WHERE id=? FOR UPDATE", String.class, report.targetId());
                if (states.isEmpty()) throw missing("商品不存在");
                if ("TRADING".equals(states.get(0))) throw conflict("商品存在进行中的交易，请先处理交易");
                jdbc.update("UPDATE market_goods SET status='HIDDEN',updated_at=CURRENT_TIMESTAMP WHERE id=?", report.targetId());
            }
            case "DISABLE_USER" -> {
                if (!"USER".equals(report.targetType())) throw bad("禁用账号仅适用于用户举报");
                setUserStatus(admin, report.targetId(), 1);
            }
            default -> throw bad("处置类型不正确");
        }
        jdbc.update("UPDATE community_report SET status=?,resolution=?,resolver_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                decision, text(note, 1000), admin, reportId);
        audit(admin, reportId, "REPORT_" + decision, operation + ": " + text(note, 1000));
        return reportById(reportId, false);
    }

    @Transactional
    public AppealView appeal(long actor, long reportId, String reason) {
        lockActors(actor, null, false);
        ReportView report = reportById(reportId, true);
        if (actor != report.reporterId()) throw forbidden("只有举报人可以申诉");
        if ("OPEN".equals(report.status())) throw conflict("举报尚未处理");
        if (count("SELECT COUNT(*) FROM community_appeal WHERE report_id=?", reportId) > 0) throw conflict("该举报已经提交过申诉");
        long id = insert("INSERT INTO community_appeal(report_id,appellant_id,reason) VALUES(?,?,?)", reportId, actor, text(reason, 1000));
        audit(actor, reportId, "APPEAL_CREATED", text(reason, 1000));
        return appealById(id, false);
    }

    public List<AppealView> reportAppeals(long actor, long reportId) {
        ReportView report = reportById(reportId, false);
        if (report.reporterId() != actor) throw forbidden("只能查看自己的申诉");
        return jdbc.query("SELECT * FROM community_appeal WHERE report_id=? ORDER BY id", this::mapAppeal, reportId);
    }

    public Page<AppealView> appeals(String status, int page, int size) {
        int offset = offset(page, size);
        String filter = filter(status, Set.of("OPEN", "UPHELD", "REJECTED"));
        String where = filter.isEmpty() ? "" : " WHERE status=?";
        Object[] args = filter.isEmpty() ? new Object[]{size, offset} : new Object[]{filter, size, offset};
        return new Page<>(jdbc.query("SELECT * FROM community_appeal" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                this::mapAppeal, args), page, size, filter.isEmpty() ? count("SELECT COUNT(*) FROM community_appeal")
                : count("SELECT COUNT(*) FROM community_appeal WHERE status=?", filter));
    }

    @Transactional
    public AppealView resolveAppeal(long admin, long appealId, String decision, String note) {
        lockActors(admin, null, true);
        AppealView preliminary = appealById(appealId, false);
        reportById(preliminary.reportId(), true);
        AppealView appeal = appealById(appealId, true);
        if (!"OPEN".equals(appeal.status())) throw conflict("申诉已处理");
        if (!"UPHELD".equals(decision) && !"REJECTED".equals(decision)) throw bad("申诉处理结果不正确");
        jdbc.update("UPDATE community_appeal SET status=?,resolution=?,resolver_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                decision, text(note, 1000), admin, appealId);
        if ("UPHELD".equals(decision)) {
            jdbc.update("UPDATE community_report SET status='OPEN',resolution=NULL,resolver_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    appeal.reportId());
        }
        audit(admin, appeal.reportId(), "APPEAL_" + decision, text(note, 1000));
        return appealById(appealId, false);
    }

    public List<AuditView> auditTrail(long reportId) {
        reportById(reportId, false);
        return jdbc.query("SELECT * FROM community_audit WHERE report_id=? ORDER BY id",
                (rs, row) -> new AuditView(rs.getLong("id"), rs.getLong("actor_id"), rs.getLong("report_id"),
                        rs.getString("action"), rs.getString("detail"), time(rs, "created_at")), reportId);
    }

    @Transactional
    public FavoriteView favorite(long actor, long goodsId) {
        var sellers = jdbc.queryForList("SELECT seller_id FROM market_goods WHERE id=?", Long.class, goodsId);
        if (sellers.isEmpty()) throw missing("商品不存在");
        UserState seller = lockActors(actor, sellers.get(0), false);
        if (seller == null || seller.status() != 0 || seller.verified() != 1) throw conflict("商品当前不可用");
        var goods = jdbc.queryForList("SELECT status FROM market_goods WHERE id=? FOR UPDATE", String.class, goodsId);
        if (goods.isEmpty()) throw missing("商品不存在");
        if (!"ON_SALE".equals(goods.get(0))) throw conflict("只能收藏在售商品");
        jdbc.update("INSERT INTO community_favorite(user_id,goods_id) VALUES(?,?) ON DUPLICATE KEY UPDATE goods_id=goods_id", actor, goodsId);
        return favoriteByIds(actor, goodsId);
    }

    @Transactional
    public void unfavorite(long actor, long goodsId) {
        lockActors(actor, null, false);
        jdbc.update("DELETE FROM community_favorite WHERE user_id=? AND goods_id=?", actor, goodsId);
    }

    public Page<FavoriteView> favorites(long actor, int page, int size) {
        int offset = offset(page, size);
        return new Page<>(jdbc.query("SELECT f.id,f.goods_id,g.title,g.price,g.status,f.created_at FROM community_favorite f JOIN market_goods g ON g.id=f.goods_id JOIN app_user seller ON seller.id=g.seller_id WHERE f.user_id=? AND g.status<>'HIDDEN' AND seller.status=0 AND seller.email_verified=1 ORDER BY f.id DESC LIMIT ? OFFSET ?",
                this::mapFavorite, actor, size, offset), page, size,
                count("SELECT COUNT(*) FROM community_favorite f JOIN market_goods g ON g.id=f.goods_id JOIN app_user seller ON seller.id=g.seller_id WHERE f.user_id=? AND g.status<>'HIDDEN' AND seller.status=0 AND seller.email_verified=1", actor));
    }

    @Transactional
    public WantedView wanted(long actor, String title, String description, BigDecimal maxPrice, String category) {
        lockActors(actor, null, false);
        String selectedCategory = category == null ? "OTHER" : category;
        if (!CATEGORIES.contains(selectedCategory)) throw bad("分类不正确");
        long id = insert("INSERT INTO community_wanted(user_id,title,description,max_price,category) VALUES(?,?,?,?,?)",
                actor, text(title, 100), text(description, 2000), maxPrice, selectedCategory);
        return wantedById(id, false);
    }

    public Page<WantedView> wantedList(String query, String category, int page, int size) {
        int offset = offset(page, size);
        String q = query == null ? "" : query.strip();
        if (q.length() > 100) throw bad("关键词最多100个字符");
        String selectedCategory = category == null ? "" : category;
        if (!selectedCategory.isEmpty() && !CATEGORIES.contains(selectedCategory)) throw bad("分类不正确");
        String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " WHERE status='OPEN' AND EXISTS(SELECT 1 FROM app_user u WHERE u.id=community_wanted.user_id AND u.status=0 AND u.email_verified=1) AND (title LIKE ? ESCAPE '!' OR description LIKE ? ESCAPE '!')";
        String sql = selectedCategory.isEmpty() ? where : where + " AND category=?";
        Object[] args = selectedCategory.isEmpty() ? new Object[]{pattern, pattern, size, offset}
                : new Object[]{pattern, pattern, selectedCategory, size, offset};
        long total = selectedCategory.isEmpty() ? count("SELECT COUNT(*) FROM community_wanted" + sql, pattern, pattern)
                : count("SELECT COUNT(*) FROM community_wanted" + sql, pattern, pattern, selectedCategory);
        return new Page<>(jdbc.query("SELECT * FROM community_wanted" + sql + " ORDER BY id DESC LIMIT ? OFFSET ?",
                this::mapWanted, args), page, size, total);
    }

    @Transactional
    public WantedView closeWanted(long actor, long wantedId) {
        lockActors(actor, null, false);
        WantedView wanted = wantedById(wantedId, true);
        if (wanted.userId() != actor) throw forbidden("只能关闭自己的求购");
        jdbc.update("UPDATE community_wanted SET status='CLOSED',updated_at=CURRENT_TIMESTAMP WHERE id=?", wantedId);
        return wantedById(wantedId, false);
    }

    @Transactional
    public UserStatusView setUserStatus(long admin, long target, int status) {
        lockActors(admin, target, true);
        if (status != 0 && status != 1) throw bad("只允许设置正常或禁用状态");
        var users = jdbc.query("SELECT status,role,email_verified FROM app_user WHERE id=? FOR UPDATE",
                (rs, row) -> new UserState(rs.getInt("status"), rs.getString("role"), rs.getInt("email_verified")), target);
        if (users.isEmpty()) throw missing("用户不存在");
        UserState user = users.get(0);
        if (target == admin || "ADMIN".equals(user.role())) throw forbidden("不能修改自己或管理员账号的状态");
        if (user.status() == 2) throw conflict("已注销账号不能恢复或禁用");
        jdbc.update("UPDATE app_user SET status=?,session_version=session_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, target);
        insert("INSERT INTO community_user_audit(actor_id,target_user_id,new_status) VALUES(?,?,?)", admin, target, status);
        return new UserStatusView(target, status);
    }

    private long targetOwner(String type, long id) {
        List<Long> owners = switch (type) {
            case "GOODS" -> jdbc.queryForList("SELECT seller_id FROM market_goods WHERE id=? FOR UPDATE", Long.class, id);
            case "USER" -> jdbc.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", Long.class, id);
            case "REVIEW" -> jdbc.queryForList("SELECT reviewer_id FROM community_review WHERE id=? FOR UPDATE", Long.class, id);
            default -> throw bad("举报对象类型不正确");
        };
        if (owners.isEmpty()) throw missing("举报对象不存在");
        return owners.get(0);
    }

    private void ensureUserExists(long id) {
        if (count("SELECT COUNT(*) FROM app_user WHERE id=?", id) == 0) throw missing("用户不存在");
    }

    private UserState lockActors(long actor, Long target, boolean adminRequired) {
        UserState targetState = null;
        long[] ids = target == null || target == actor ? new long[]{actor}
                : actor < target ? new long[]{actor, target} : new long[]{target, actor};
        for (long id : ids) {
            var users = jdbc.query("SELECT status,role,email_verified FROM app_user WHERE id=? FOR UPDATE",
                    (rs, row) -> new UserState(rs.getInt("status"), rs.getString("role"), rs.getInt("email_verified")), id);
            if (users.isEmpty()) {
                if (id == actor) throw new ApiException(HttpStatus.UNAUTHORIZED, "登录状态已失效");
                throw missing("用户不存在");
            }
            UserState user = users.get(0);
            if (target != null && id == target) targetState = user;
            if (id == actor && (user.status() != 0 || user.verified() != 1)) throw forbidden("账号当前不可用");
            if (id == actor && adminRequired && !"ADMIN".equals(user.role())) throw forbidden("需要管理员权限");
        }
        return targetState;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("数据创建失败");
        return key.getKey().longValue();
    }

    private ReviewView reviewById(long id) {
        return jdbc.queryForObject("SELECT * FROM community_review WHERE id=?", this::mapReview, id);
    }

    private ReportView reportById(long id, boolean lock) {
        var reports = jdbc.query("SELECT * FROM community_report WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::mapReport, id);
        if (reports.isEmpty()) throw missing("举报不存在");
        return reports.get(0);
    }

    private AppealView appealById(long id, boolean lock) {
        var appeals = jdbc.query("SELECT * FROM community_appeal WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::mapAppeal, id);
        if (appeals.isEmpty()) throw missing("申诉不存在");
        return appeals.get(0);
    }

    private WantedView wantedById(long id, boolean lock) {
        var wanted = jdbc.query("SELECT * FROM community_wanted WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::mapWanted, id);
        if (wanted.isEmpty()) throw missing("求购不存在");
        return wanted.get(0);
    }

    private FavoriteView favoriteByIds(long userId, long goodsId) {
        return jdbc.queryForObject("SELECT f.id,f.goods_id,g.title,g.price,g.status,f.created_at FROM community_favorite f JOIN market_goods g ON g.id=f.goods_id WHERE f.user_id=? AND f.goods_id=? FOR UPDATE",
                this::mapFavorite, userId, goodsId);
    }

    private void audit(long actor, long report, String action, String detail) {
        insert("INSERT INTO community_audit(actor_id,report_id,action,detail) VALUES(?,?,?,?)", actor, report, action, detail);
    }

    private int offset(int page, int size) {
        if (page < 1 || size < 1 || size > 50 || page > 100000) throw bad("页码需大于0，每页1至50条");
        return (page - 1) * size;
    }

    private String text(String value, int maxLength) {
        if (value == null) throw bad("内容不能为空");
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.codePoints().allMatch(character -> Character.isWhitespace(character)
                || Character.isSpaceChar(character) || Character.getType(character) == Character.FORMAT)) {
            throw bad("内容不能为空");
        }
        if (cleaned.length() > maxLength) throw bad("内容超过长度限制");
        return cleaned;
    }

    private String filter(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return "";
        if (!allowed.contains(value)) throw bad("状态不正确");
        return value;
    }

    private LocalDateTime time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private ReviewView mapReview(ResultSet rs, int row) throws SQLException {
        return new ReviewView(rs.getLong("id"), rs.getLong("trade_id"), rs.getLong("reviewer_id"),
                rs.getLong("reviewee_id"), rs.getInt("rating"), rs.getString("review_text"), time(rs, "created_at"));
    }

    private ReportView mapReport(ResultSet rs, int row) throws SQLException {
        return new ReportView(rs.getLong("id"), rs.getLong("reporter_id"), rs.getString("target_type"), rs.getLong("target_id"),
                rs.getString("reason"), rs.getString("status"), rs.getString("resolution"),
                rs.getObject("resolver_id", Long.class), time(rs, "created_at"), time(rs, "updated_at"));
    }

    private AppealView mapAppeal(ResultSet rs, int row) throws SQLException {
        return new AppealView(rs.getLong("id"), rs.getLong("report_id"), rs.getLong("appellant_id"), rs.getString("reason"),
                rs.getString("status"), rs.getString("resolution"), rs.getObject("resolver_id", Long.class),
                time(rs, "created_at"), time(rs, "updated_at"));
    }

    private FavoriteView mapFavorite(ResultSet rs, int row) throws SQLException {
        return new FavoriteView(rs.getLong("id"), rs.getLong("goods_id"), rs.getString("title"), rs.getBigDecimal("price"),
                rs.getString("status"), time(rs, "created_at"));
    }

    private WantedView mapWanted(ResultSet rs, int row) throws SQLException {
        return new WantedView(rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"), rs.getString("description"),
                rs.getBigDecimal("max_price"), rs.getString("category"), rs.getString("status"),
                time(rs, "created_at"), time(rs, "updated_at"));
    }

    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }

    private record TradeParties(long buyerId, long sellerId, String status) { }
    private record UserState(int status, String role, int verified) { }
    public record Page<T>(List<T> items, int page, int size, long total) { }
    public record ReviewView(long id, long tradeId, long reviewerId, long revieweeId, int rating, String comment, LocalDateTime createdAt) { }
    public record ReputationView(long userId, long reviewCount, BigDecimal averageRating) { }
    public record ReportView(long id, long reporterId, String targetType, long targetId, String reason, String status,
                             String resolution, Long resolverId, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record AppealView(long id, long reportId, long appellantId, String reason, String status, String resolution,
                             Long resolverId, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record AuditView(long id, long actorId, long reportId, String action, String detail, LocalDateTime createdAt) { }
    public record FavoriteView(long id, long goodsId, String title, BigDecimal price, String status, LocalDateTime createdAt) { }
    public record WantedView(long id, long userId, String title, String description, BigDecimal maxPrice, String category,
                             String status, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record UserStatusView(long userId, int status) { }
}
