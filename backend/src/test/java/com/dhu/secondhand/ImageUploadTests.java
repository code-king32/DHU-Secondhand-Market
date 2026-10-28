package com.dhu.secondhand;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.jdbc.core.JdbcTemplate;
import com.dhu.secondhand.marketplace.ImageStorage;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key",
        "app.images.max-per-user=2", "app.images.max-bytes-per-user=1024",
        "spring.mail.username=test@example.com", "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:image_upload_test;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
@Sql("/sql/marketplace-schema.sql")
class ImageUploadTests {
    @TempDir static Path directory;
    @DynamicPropertySource
    static void imageDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.images.directory", () -> directory.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @MockitoBean JavaMailSender mailSender;
    @MockitoBean Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImageStorage storage;
    private static final Instant NOW = Instant.parse("2026-10-28T04:00:00Z");

    @BeforeEach
    void resetImages() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("DELETE FROM app_goods_image");
        jdbc.update("DELETE FROM app_uploaded_image");
    }

    @Test
    void uploadsPreviewsPublishesAndEditsWithUploadedAndLegacyImages() throws Exception {
        for (String format : List.of("png", "jpg")) {
            byte[] bytes = image(format);
            String body = mvc.perform(multipart("/api/images").file(new MockMultipartFile(
                            "file", "../../original." + format, "application/octet-stream", bytes))
                            .session(session(1)).header("Origin", "http://localhost:5173"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String url = json.readTree(body).get("url").asText();
            assertTrue(url.matches("/api/images/[0-9a-f]{32}\\." + format));
            byte[] downloaded = mvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(content().contentType(format.equals("png") ? "image/png" : "image/jpeg"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertNotNull(ImageIO.read(new ByteArrayInputStream(downloaded)));
            assertTrue(Files.isRegularFile(directory.resolve(url.substring(url.lastIndexOf('/') + 1))));

            var goods = Map.of("title", "图片商品", "description", "本地上传图片", "price", "12.00",
                    "category", "OTHER", "imageUrls", List.of(url, "https://example.com/legacy.png"));
            String created = mvc.perform(post("/api/goods").session(session(1))
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(goods)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.imageUrls[0]").value(url))
                    .andReturn().getResponse().getContentAsString();
            long id = json.readTree(created).get("id").asLong();
            mvc.perform(put("/api/goods/{id}", id).session(session(1)).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(goods)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrls[1]").value("https://example.com/legacy.png"));
            mvc.perform(get("/api/goods/{id}", id)).andExpect(jsonPath("$.imageUrls[0]").value(url));
        }
    }

    @Test
    void onlyActiveAuthenticatedUsersCanUpload() throws Exception {
        var file = new MockMultipartFile("file", "a.png", "image/png", image("png"));
        mvc.perform(multipart("/api/images").file(file)).andExpect(status().isUnauthorized());
        for (long user : new long[] {5, 6}) {
            mvc.perform(multipart("/api/images").file(file).session(session(user))).andExpect(status().isForbidden());
        }
        mvc.perform(multipart("/api/images").file(file).session(session(1))
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/images").file(file).session(session(1))
                .header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        // Multipart 只对图片接口开放，其他写接口仍要求 JSON。
        mvc.perform(multipart("/api/goods").file(file).session(session(1))).andExpect(status().isForbidden());
        mvc.perform(post("/api/goods").session(session(1)).contentType("application/*").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsFakeEmptyOversizedImagesAndInvalidPaths() throws Exception {
        for (byte[] invalid : List.of(new byte[0], "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47})) {
            mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "fake.png", "image/png", invalid))
                    .session(session(1))).andExpect(status().isBadRequest());
        }
        byte[] gif = image("gif");
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "fake.png", "image/png", gif))
                .session(session(1))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/images").file(new MockMultipartFile(
                        "file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1])).session(session(1)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").value("每张图片不能超过 5 MB"));
        mvc.perform(multipart("/api/images").session(session(1))).andExpect(status().isBadRequest());
        // 先检查声明尺寸，避免解码分辨率过大的压缩图片。
        byte[] hugeDimensions = image("png");
        ByteBuffer.wrap(hugeDimensions).putInt(16, 5000).putInt(20, 5000);
        var crc = new CRC32();
        crc.update(hugeDimensions, 12, 17);
        ByteBuffer.wrap(hugeDimensions).putInt(29, (int) crc.getValue());
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "huge.png", "image/png", hugeDimensions))
                        .session(session(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("图片分辨率过大，请压缩到 2000 万像素以内"));
        mvc.perform(get("/api/images/secrets.properties")).andExpect(status().isNotFound());
        mvc.perform(get("/api/images/00000000000000000000000000000000.png")).andExpect(status().isNotFound());
        for (String url : List.of("/api/images/../secrets.properties", "//evil.example/a.png", "/api/images/fake.svg")) {
            mvc.perform(post("/api/goods").session(session(1)).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(Map.of("title", "图片商品", "description", "描述", "price", "1.00",
                                    "imageUrls", List.of(url)))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void realHttpMultipartLimitReturnsActionableJson() throws Exception {
        String boundary = "ImageUploadTestBoundary";
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[5 * 1024 * 1024 + 1]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/images"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertEquals("每张图片不能超过 5 MB", json.readTree(response.body()).get("message").asText());
    }

    @Test
    void enforcesOwnershipAndPerUserCountAndByteQuotas() throws Exception {
        String first = upload(1, "png");
        mvc.perform(post("/api/goods").session(session(2)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(goods(first))))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM market_goods", Integer.class));
        upload(1, "png");
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "third.png", "image/png", image("png")))
                        .session(session(1))).andExpect(status().isConflict());
        upload(2, "jpg");
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "second.jpg", "image/jpeg", image("jpg")))
                        .session(session(2))).andExpect(status().isConflict());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image WHERE owner_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image WHERE owner_id=2", Integer.class));
    }

    @Test
    void cleansOnlyUnreferencedImagesAfterGracePeriodAndFreesQuota() throws Exception {
        String used = upload(1, "png");
        String orphan = upload(1, "jpg");
        String created = mvc.perform(post("/api/goods").session(session(1)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(goods(used))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();
        when(clock.instant()).thenReturn(NOW.plusSeconds(25 * 3600));
        storage.cleanupUnused();
        mvc.perform(get(used)).andExpect(status().isOk());
        mvc.perform(get(orphan)).andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image", Integer.class));
        mvc.perform(put("/api/goods/{id}", id).session(session(1)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(goods())))
                .andExpect(status().isOk());
        when(clock.instant()).thenReturn(NOW.plusSeconds(48 * 3600));
        storage.cleanupUnused();
        mvc.perform(get(used)).andExpect(status().isOk());
        when(clock.instant()).thenReturn(NOW.plusSeconds(50 * 3600));
        storage.cleanupUnused();
        mvc.perform(get(used)).andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image", Integer.class));
        upload(1, "png");
    }

    @Test
    void concurrentCleanupAndPublicationCannotLeavePublishedGoodsWithDeletedImage() throws Exception {
        String url = upload(1, "png");
        when(clock.instant()).thenReturn(NOW.plusSeconds(25 * 3600));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var publish = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/goods").session(session(1)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(goods(url)))).andReturn().getResponse().getStatus();
            });
            var cleanup = executor.submit(() -> { start.await(); storage.cleanupUnused(); return true; });
            start.countDown();
            int result = publish.get(10, java.util.concurrent.TimeUnit.SECONDS);
            cleanup.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (result == 201) mvc.perform(get(url)).andExpect(status().isOk());
            else {
                assertTrue(result == 400 || result == 403);
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM market_goods", Integer.class));
            }
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private String upload(long user, String format) throws Exception {
        String result = mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "a." + format,
                        "image/" + format, image(format))).session(session(user)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("url").asText();
    }

    private Map<String, Object> goods(String... urls) {
        return Map.of("title", "图片商品", "description", "描述", "price", "1.00", "imageUrls", List.of(urls));
    }

    private MockHttpSession session(long user) {
        var session = new MockHttpSession();
        session.setAttribute("userId", user);
        return session;
    }

    private byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
