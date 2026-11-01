package com.dhu.secondhand;
import com.dhu.secondhand.common.SessionAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key",
 "spring.mail.username=test@example.com", "spring.datasource.url=jdbc:h2:mem:session_write;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
 "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
@Sql("/sql/community-schema.sql")
class SessionRevocationWriteTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SessionAuth auth;
    @MockitoBean JavaMailSender mail;
    private MockHttpSession session(long id) {
        var session = new MockHttpSession();
        session.setAttribute("userId", id);
        session.setAttribute("sessionVersion", 0);
        return session;
    }
    private void revokeAfterAuthentication() {
        doAnswer(invocation -> {
            long id = (long) invocation.callRealMethod();
            jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?", id);
            return id;
        }).when(auth).requireUserId(any());
    }
    @Test void revokedSessionCannotPublishGoods() throws Exception {
        revokeAfterAuthentication();
        mvc.perform(post("/api/goods").session(session(1)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"书\",\"description\":\"说明\",\"price\":1}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM market_goods WHERE title='书'", Integer.class));
    }
    @Test void revokedSessionCannotPublishWanted() throws Exception {
        revokeAfterAuthentication();
        mvc.perform(post("/api/wanted").session(session(1)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"书\",\"description\":\"说明\",\"maxPrice\":1,\"category\":\"OTHER\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM community_wanted", Integer.class));
    }
    @Test void revokedSessionCannotUploadImage() throws Exception {
        revokeAfterAuthentication();
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out);
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "a.png", "image/png", out.toByteArray()))
                .session(session(1))).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_uploaded_image", Integer.class));
    }
}
