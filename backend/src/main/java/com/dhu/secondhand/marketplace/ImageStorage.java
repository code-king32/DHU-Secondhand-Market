package com.dhu.secondhand.marketplace;

import com.dhu.secondhand.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class ImageStorage {
    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000;
    private final Path directory;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int maxPerUser;
    private final long maxBytes;
    private final int retentionHours;

    public ImageStorage(@Value("${app.images.directory:uploads/images}") String directory,
            JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
            @Value("${app.images.max-per-user:100}") int maxPerUser,
            @Value("${app.images.max-bytes-per-user:104857600}") long maxBytes,
            @Value("${app.images.unused-retention-hours:24}") int retentionHours) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxBytes = Math.max(1, maxBytes);
        this.retentionHours = Math.max(1, retentionHours);
    }

    public String store(long owner, MultipartFile file) {
        if (file.isEmpty()) throw invalid("请选择图片");
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "每张图片不能超过 5 MB");
        }
        BufferedImage image;
        String format;
        // 读取真实格式并重新编码，不信任扩展名或浏览器提供的 Content-Type。
        try (var input = new MemoryCacheImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("仅支持有效的 JPG、PNG 图片");
            ImageReader reader = readers.next();
            try {
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw invalid("仅支持 JPG、PNG 图片");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw invalid("图片分辨率过大，请压缩到 2000 万像素以内");
                }
                image = reader.read(0);
                if (image == null) throw invalid("图片无法读取，请重新选择");
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("图片无法读取，请重新选择有效的 JPG、PNG 图片");
        }
        String name = UUID.randomUUID().toString().replace("-", "") + (format.equals("jpeg") ? ".jpg" : ".png");
        byte[] encoded;
        try {
            var bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, bytes)) throw new IOException("No image writer");
            encoded = bytes.toByteArray();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败，请稍后重试");
        } finally {
            image.flush();
        }
        if (encoded.length > MAX_BYTES) throw invalid("图片重新编码后超过 5 MB，请压缩后再上传");
        Path path = resolve(name);
        try {
            tx.executeWithoutResult(status -> {
                lockOwner(owner, true);
                long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image WHERE owner_id=?", Long.class, owner);
                long used = jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM app_uploaded_image WHERE owner_id=?", Long.class, owner);
                if (count >= maxPerUser || used + encoded.length > maxBytes) {
                    throw new ApiException(HttpStatus.CONFLICT, "图片存储额度已满，请移除不用的商品图片并等待自动清理");
                }
                LocalDateTime now = now();
                jdbc.update("INSERT INTO app_uploaded_image(name,owner_id,byte_size,created_at,unused_since,status) VALUES(?,?,?,?,?,'ACTIVE')",
                        name, owner, encoded.length, now, now);
                try {
                    Files.createDirectories(directory);
                    Files.write(path, encoded, StandardOpenOption.CREATE_NEW);
                } catch (IOException exception) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败，请稍后重试");
                }
            });
        } catch (RuntimeException exception) {
            try { Files.deleteIfExists(path); }
            catch (IOException cleanup) { log.warn("Could not remove failed image upload {}", name); }
            throw exception;
        }
        return "/api/images/" + name;
    }

    // 调用者已在商品事务中锁住所有者；图片校验和引用变更随商品一起提交。
    public void bind(long owner, long goodsId, List<String> urls, List<String> previousUrls) {
        var names = new HashSet<String>();
        for (String url : urls == null ? List.<String>of() : urls) {
            if (!url.startsWith("/api/images/")) continue;
            String name = url.substring("/api/images/".length());
            Path path = resolve(name);
            var owners = jdbc.query("SELECT owner_id,status FROM app_uploaded_image WHERE name=? FOR UPDATE",
                    (rs, row) -> new Owner(rs.getLong(1), rs.getString(2)), name);
            if (owners.isEmpty() && previousUrls.contains(url) && Files.isRegularFile(path)) {
                // 只接管升级前已属于当前商品的站内图片，不认领未知文件或他人图片。
                try {
                    jdbc.update("INSERT INTO app_uploaded_image(name,owner_id,byte_size,created_at,status) VALUES(?,?,?,?,'ACTIVE')",
                            name, owner, Files.size(path), now());
                } catch (IOException exception) { throw invalid("图片无法读取，请重新上传"); }
            } else if (owners.isEmpty() || owners.get(0).id != owner) {
                throw new ApiException(HttpStatus.FORBIDDEN, "只能使用自己上传的商品图片");
            } else if (!owners.get(0).status.equals("ACTIVE")) {
                throw invalid("图片已进入清理流程，请重新上传");
            }
            if (!Files.isRegularFile(path)) throw invalid("图片已不存在，请重新上传");
            names.add(name);
        }
        var oldNames = jdbc.queryForList("SELECT image_name FROM app_goods_image WHERE goods_id=?", String.class, goodsId);
        jdbc.update("DELETE FROM app_goods_image WHERE goods_id=?", goodsId);
        for (String name : names) {
            jdbc.update("INSERT INTO app_goods_image(goods_id,image_name) VALUES(?,?)", goodsId, name);
            jdbc.update("UPDATE app_uploaded_image SET unused_since=NULL WHERE name=?", name);
        }
        for (String name : oldNames) {
            jdbc.update("""
                    UPDATE app_uploaded_image SET unused_since=? WHERE name=?
                    AND NOT EXISTS (SELECT 1 FROM app_goods_image WHERE image_name=?)
                    """, now(), name, name);
        }
    }

    public void cleanupUnused() {
        var cutoff = now().minusHours(retentionHours);
        var candidates = jdbc.queryForList("""
                SELECT name FROM app_uploaded_image WHERE status='DELETING' OR
                (unused_since IS NOT NULL AND unused_since<=?) ORDER BY created_at LIMIT 200
                """, String.class, cutoff);
        for (String name : candidates) {
            try {
                Boolean deleting = tx.execute(status -> {
                    var owners = jdbc.queryForList("SELECT owner_id FROM app_uploaded_image WHERE name=?", Long.class, name);
                    if (owners.isEmpty()) return false;
                    lockOwner(owners.get(0), false);
                    var rows = jdbc.query("SELECT status,unused_since FROM app_uploaded_image WHERE name=? FOR UPDATE",
                            (rs, row) -> new Unused(rs.getString(1), rs.getObject(2, LocalDateTime.class)), name);
                    if (rows.isEmpty() || jdbc.queryForObject("SELECT COUNT(*) FROM app_goods_image WHERE image_name=?", Long.class, name) > 0) return false;
                    Unused unused = rows.get(0);
                    if (!unused.status.equals("DELETING") && (unused.since == null || unused.since.isAfter(cutoff))) return false;
                    jdbc.update("UPDATE app_uploaded_image SET status='DELETING' WHERE name=?", name);
                    return true;
                });
                if (Boolean.TRUE.equals(deleting)) {
                    Files.deleteIfExists(resolve(name));
                    jdbc.update("DELETE FROM app_uploaded_image WHERE name=? AND status='DELETING'", name);
                }
            } catch (IOException | RuntimeException exception) {
                log.warn("Image cleanup will retry {}: {}", name, exception.getClass().getSimpleName());
            }
        }
    }

    private void lockOwner(long owner, boolean requireActive) {
        var states = jdbc.query("SELECT status,email_verified FROM app_user WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getInt(1) == 0 && rs.getInt(2) == 1, owner);
        if (requireActive && (states.isEmpty() || !states.get(0))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号当前不可用");
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }
    private record Owner(long id, String status) { }
    private record Unused(String status, LocalDateTime since) { }

    private Path resolve(String name) {
        if (!name.matches("[0-9a-f]{32}\\.(jpg|png)")) throw notFound();
        Path path = directory.resolve(name).normalize();
        if (!path.startsWith(directory)) throw notFound();
        return path;
    }

    public Path find(String name) {
        Path path = resolve(name);
        if (!Files.isRegularFile(path)) throw notFound();
        return path;
    }

    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "图片不存在"); }
}
