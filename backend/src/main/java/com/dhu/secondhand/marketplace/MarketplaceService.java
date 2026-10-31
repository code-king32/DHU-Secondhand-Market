package com.dhu.secondhand.marketplace;

import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.common.TextValidation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.dhu.secondhand.marketplace.MarketDtos.*;

@Service
public class MarketplaceService {
    private static final Set<String> CATEGORIES = Set.of("TEXTBOOK", "ELECTRONICS", "DAILY", "CLOTHING", "OTHER");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ImageStorage imageStorage;

    public MarketplaceService(JdbcTemplate jdbc, ObjectMapper json, ImageStorage imageStorage) {
        this.jdbc = jdbc;
        this.json = json;
        this.imageStorage = imageStorage;
    }

    @Transactional
    public GoodsView create(long actor, GoodsWriteRequest request) {
        String title = requiredText(request.title(), 100, "标题");
        String description = requiredText(request.description(), 5000, "描述");
        lockAccounts(actor, actor, actor, true);
        String category = category(request.category());
        List<String> imageUrls = imageStorage.normalizeUrls(request.imageUrls());
        String images = images(imageUrls);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO market_goods (seller_id,title,description,price,category,image_urls,status)
                    VALUES (?,?,?,?,?,?,'ON_SALE')
                    """, new String[]{"id"});
            statement.setLong(1, actor);
            statement.setString(2, title);
            statement.setString(3, description);
            statement.setBigDecimal(4, request.price());
            statement.setString(5, category);
            statement.setString(6, images);
            return statement;
        }, key);
        long id = key.getKey().longValue();
        imageStorage.bind(actor, id, imageUrls, List.of());
        return findGoods(id, false);
    }

    @Transactional
    public GoodsView edit(long actor, long id, GoodsWriteRequest request) {
        String title = requiredText(request.title(), 100, "标题");
        String description = requiredText(request.description(), 5000, "描述");
        lockAccounts(actor, actor, actor, true);
        GoodsView goods = findGoods(id, true);
        owner(actor, goods.sellerId());
        editable(goods);
        List<String> imageUrls = imageStorage.normalizeUrls(request.imageUrls());
        imageStorage.bind(actor, id, imageUrls, goods.imageUrls());
        jdbc.update("""
                UPDATE market_goods SET title=?,description=?,price=?,category=?,image_urls=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """, title, description, request.price(),
                category(request.category()), images(imageUrls), id);
        return findGoods(id, false);
    }

    @Transactional
    public GoodsView shelf(long actor, long id, boolean onSale) {
        lockAccounts(actor, actor, actor, true);
        GoodsView goods = findGoods(id, true);
        owner(actor, goods.sellerId());
        editable(goods);
        jdbc.update("UPDATE market_goods SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                onSale ? "ON_SALE" : "OFF_SHELF", id);
        return findGoods(id, false);
    }

    public GoodsView detail(long id) {
        GoodsView goods = findGoods(id, false);
        if (Set.of("OFF_SHELF", "HIDDEN").contains(goods.status())
                || !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM app_user WHERE id=? AND status=0 AND email_verified=1)",
                    Boolean.class, goods.sellerId()))) {
            throw error(HttpStatus.NOT_FOUND, "商品不存在或已下架");
        }
        return goods;
    }

    public GoodsView manage(long actor, long id) {
        GoodsView goods = findGoods(id, false);
        owner(actor, goods.sellerId());
        return goods;
    }

    public Page<GoodsView> list(String keyword, String category, int page, int size, Long sellerId) {
        pagination(page, size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sellerId == null) {
            where.append(" AND status='ON_SALE'");
            where.append(" AND EXISTS(SELECT 1 FROM app_user seller WHERE seller.id=market_goods.seller_id AND seller.status=0 AND seller.email_verified=1)");
        } else {
            where.append(" AND seller_id=?");
            args.add(sellerId);
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category=?");
            args.add(category(category));
        }
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 100) throw error(HttpStatus.BAD_REQUEST, "搜索词过长");
            String pattern = "%" + keyword.strip().toLowerCase(Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(description) LIKE ? ESCAPE '!')");
            args.add(pattern);
            args.add(pattern);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM market_goods" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1L) * size);
        List<GoodsView> items = jdbc.query("SELECT * FROM market_goods" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                goodsMapper(), pageArgs.toArray());
        return new Page<>(items, total, page, size);
    }

    @Transactional
    public TradeView apply(long buyer, long goodsId) {
        GoodsView initial = findGoods(goodsId, false);
        lockAccounts(initial.sellerId(), buyer, buyer, true);
        GoodsView goods = findGoods(goodsId, true);
        if (goods.sellerId() == buyer) throw error(HttpStatus.BAD_REQUEST, "不能申请自己的商品");
        if (!goods.status().equals("ON_SALE")) throw error(HttpStatus.CONFLICT, "商品当前不可申请交易");
        var duplicate = jdbc.queryForList("""
                SELECT id FROM market_trade
                WHERE goods_id=? AND buyer_id=? AND status IN ('PENDING','ACCEPTED') FOR UPDATE
                """, Long.class, goodsId, buyer);
        if (!duplicate.isEmpty()) throw error(HttpStatus.CONFLICT, "已有待处理的交易申请");
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO market_trade (goods_id,seller_id,buyer_id,agreed_price,status)
                    VALUES (?,?,?,?,'PENDING')
                    """, new String[]{"id"});
            statement.setLong(1, goodsId);
            statement.setLong(2, goods.sellerId());
            statement.setLong(3, buyer);
            statement.setBigDecimal(4, goods.price());
            return statement;
        }, key);
        return findTrade(key.getKey().longValue(), false);
    }

    public Page<TradeView> trades(long actor, String role, int page, int size) {
        pagination(page, size);
        String normalized = role == null ? "ALL" : role.toUpperCase(Locale.ROOT);
        String where;
        Object[] actors;
        switch (normalized) {
            case "BUYER" -> { where = " WHERE buyer_id=?"; actors = new Object[]{actor}; }
            case "SELLER" -> { where = " WHERE seller_id=?"; actors = new Object[]{actor}; }
            case "ALL" -> { where = " WHERE (buyer_id=? OR seller_id=?)"; actors = new Object[]{actor, actor}; }
            default -> throw error(HttpStatus.BAD_REQUEST, "交易角色无效");
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM market_trade" + where, Long.class, actors);
        List<Object> args = new ArrayList<>(List.of(actors));
        args.add(size);
        args.add((page - 1L) * size);
        return new Page<>(jdbc.query("SELECT * FROM market_trade" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                tradeMapper(), args.toArray()), total, page, size);
    }

    public TradeView trade(long actor, long id) {
        TradeView trade = findTrade(id, false);
        participant(actor, trade);
        return trade;
    }

    @Transactional
    public TradeView accept(long actor, long id) {
        TradeView initial = findTrade(id, false);
        lockAccounts(initial.sellerId(), initial.buyerId(), actor, true);
        GoodsView goods = findGoods(initial.goodsId(), true);
        TradeView trade = findTrade(id, true);
        owner(actor, trade.sellerId());
        if (trade.status().equals("ACCEPTED")) return trade;
        if (!trade.status().equals("PENDING") || !goods.status().equals("ON_SALE")) {
            throw error(HttpStatus.CONFLICT, "该交易当前不可接受");
        }
        jdbc.update("UPDATE market_goods SET status='TRADING',updated_at=CURRENT_TIMESTAMP WHERE id=?", goods.id());
        jdbc.update("UPDATE market_trade SET status='ACCEPTED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        return findTrade(id, false);
    }

    @Transactional
    public TradeView confirm(long actor, long id) {
        TradeView initial = findTrade(id, false);
        lockAccounts(initial.sellerId(), initial.buyerId(), actor, true);
        GoodsView goods = findGoods(initial.goodsId(), true);
        TradeView trade = findTrade(id, true);
        participant(actor, trade);
        if (trade.status().equals("COMPLETED")) return trade;
        if (!trade.status().equals("ACCEPTED") || !goods.status().equals("TRADING")) {
            throw error(HttpStatus.CONFLICT, "只有已接受的交易可以确认完成");
        }
        boolean buyerConfirmed = trade.buyerConfirmed() || actor == trade.buyerId();
        boolean sellerConfirmed = trade.sellerConfirmed() || actor == trade.sellerId();
        String status = buyerConfirmed && sellerConfirmed ? "COMPLETED" : "ACCEPTED";
        jdbc.update("""
                UPDATE market_trade SET buyer_confirmed=?,seller_confirmed=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, buyerConfirmed, sellerConfirmed, status, id);
        if (status.equals("COMPLETED")) {
            jdbc.update("UPDATE market_goods SET status='SOLD',updated_at=CURRENT_TIMESTAMP WHERE id=?", goods.id());
            jdbc.update("""
                    UPDATE market_trade SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP
                    WHERE goods_id=? AND id<>? AND status='PENDING'
                    """, goods.id(), id);
        }
        return findTrade(id, false);
    }

    @Transactional
    public TradeView cancel(long actor, long id) {
        TradeView initial = findTrade(id, false);
        lockAccounts(initial.sellerId(), initial.buyerId(), actor, false);
        GoodsView goods = findGoods(initial.goodsId(), true);
        TradeView trade = findTrade(id, true);
        participant(actor, trade);
        if (trade.status().equals("CANCELLED")) return trade;
        if (trade.status().equals("COMPLETED")) throw error(HttpStatus.CONFLICT, "已完成交易不能取消");
        jdbc.update("UPDATE market_trade SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        if (trade.status().equals("ACCEPTED")) {
            if (!goods.status().equals("TRADING")) throw error(HttpStatus.CONFLICT, "商品状态异常");
            jdbc.update("UPDATE market_goods SET status='ON_SALE',updated_at=CURRENT_TIMESTAMP WHERE id=?", goods.id());
        }
        return findTrade(id, false);
    }

    private GoodsView findGoods(long id, boolean lock) {
        List<GoodsView> result = jdbc.query("SELECT * FROM market_goods WHERE id=?" + (lock ? " FOR UPDATE" : ""), goodsMapper(), id);
        if (result.isEmpty()) throw error(HttpStatus.NOT_FOUND, "商品不存在");
        return result.get(0);
    }

    private TradeView findTrade(long id, boolean lock) {
        List<TradeView> result = jdbc.query("SELECT * FROM market_trade WHERE id=?" + (lock ? " FOR UPDATE" : ""), tradeMapper(), id);
        if (result.isEmpty()) throw error(HttpStatus.NOT_FOUND, "交易不存在");
        return result.get(0);
    }

    private RowMapper<GoodsView> goodsMapper() {
        return (rs, row) -> new GoodsView(rs.getLong("id"), rs.getLong("seller_id"), rs.getString("title"),
                rs.getString("description"), rs.getBigDecimal("price"), rs.getString("category"),
                parseImages(rs.getString("image_urls")), rs.getString("status"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private RowMapper<TradeView> tradeMapper() {
        return (rs, row) -> new TradeView(rs.getLong("id"), rs.getLong("goods_id"), rs.getLong("seller_id"),
                rs.getLong("buyer_id"), rs.getBigDecimal("agreed_price"), rs.getString("status"),
                rs.getBoolean("buyer_confirmed"), rs.getBoolean("seller_confirmed"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private String category(String value) {
        String result = value == null || value.isBlank() ? "OTHER" : value.strip().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(result)) throw error(HttpStatus.BAD_REQUEST, "商品分类无效");
        return result;
    }

    private String requiredText(String value, int maxLength, String label) {
        String normalized = value == null ? "" : value.strip();
        if (TextValidation.isBlank(normalized) || normalized.length() > maxLength) {
            throw error(HttpStatus.BAD_REQUEST, "商品" + label + "不能为空或超过长度限制");
        }
        return normalized;
    }

    private String images(List<String> values) {
        List<String> safe = values == null ? List.of() : List.copyOf(values);
        try { return json.writeValueAsString(safe); }
        catch (Exception exception) { throw new IllegalStateException("图片地址序列化失败", exception); }
    }

    private List<String> parseImages(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() { }); }
        catch (Exception exception) { throw new IllegalStateException("图片地址数据无效", exception); }
    }

    private void editable(GoodsView goods) {
        if (!Set.of("ON_SALE", "OFF_SHELF").contains(goods.status())) throw error(HttpStatus.CONFLICT, "交易中或已售商品不可编辑或上下架");
    }

    private void lockAccounts(long first, long second, long actor, boolean requireBothActive) {
        // 账号统一按编号加锁，再锁商品和交易；与账号注销事务保持相同的锁顺序。
        long low = Math.min(first, second);
        long high = Math.max(first, second);
        lockAccount(low, actor, requireBothActive);
        if (high != low) lockAccount(high, actor, requireBothActive);
    }

    private void lockAccount(long userId, long actor, boolean requireActive) {
        List<AccountState> users = jdbc.query("SELECT status,email_verified FROM app_user WHERE id=? FOR UPDATE",
                (rs, row) -> new AccountState(rs.getInt("status"), rs.getInt("email_verified")), userId);
        if (users.isEmpty() || ((requireActive || userId == actor)
                && (users.get(0).status() != 0 || users.get(0).verified() != 1))) {
            throw error(userId == actor ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT, "交易方账号不可用或未认证");
        }
    }

    private record AccountState(int status, int verified) { }

    private void owner(long actor, long owner) {
        if (actor != owner) throw error(HttpStatus.FORBIDDEN, "只能操作自己的商品或交易");
    }

    private void participant(long actor, TradeView trade) {
        if (actor != trade.sellerId() && actor != trade.buyerId()) throw error(HttpStatus.FORBIDDEN, "无权访问该交易");
    }

    private void pagination(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw error(HttpStatus.BAD_REQUEST, "分页参数无效");
    }

    private ApiException error(HttpStatus status, String message) { return new ApiException(status, message); }
}
