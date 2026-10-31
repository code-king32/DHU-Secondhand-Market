package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailService;
import com.dhu.secondhand.identity.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key",
        "spring.mail.username=test@example.com", "spring.mail.test-connection=false",
        "server.servlet.session.timeout=7m",
        "spring.datasource.url=jdbc:h2:mem:runtime_http;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password="})
@Sql({"/sql/acceptance-flow-schema.sql", "/sql/runtime-schema.sql"})
class RuntimeHttpTests {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @MockitoBean private MailService mailService;
    @MockitoSpyBean private AccountAuthService accounts;

    @Test
    void servesRealHttpAndSessionCookiesWithoutLeakingSecrets() throws Exception {
        var timeout = new AtomicInteger();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            timeout.set(((HttpServletRequest) call.getArgument(1)).getSession(false).getMaxInactiveInterval());
            return result;
        }).when(accounts).openSession(anyLong(), any());
        var codes = new ConcurrentHashMap<String,String>();
        doAnswer(call -> { codes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mailService).sendVerificationCode(anyString(), anyString());
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
        String email = "runtime@mail.dhu.edu.cn";
        assertEquals(200, post(client,"/api/auth/registration-codes", Map.of("email",email)).statusCode());
        var registered = post(client,"/api/auth/register",Map.of("email",email,"code",codes.get(email),
                "password","Runtime123!", "nickname","运行验收"));
        assertEquals(201, registered.statusCode());
        assertFalse(registered.body().contains("password"));
        var loggedIn = post(client,"/api/auth/login",Map.of("email",email,"password","Runtime123!"));
        assertEquals(200,loggedIn.statusCode());
        assertEquals(420, timeout.get(), "会话有效期必须遵循部署配置");
        String sessionCookie = loggedIn.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(sessionCookie.toLowerCase().contains("httponly"));
        assertTrue(sessionCookie.toLowerCase().contains("samesite=lax"));
        var me = client.send(HttpRequest.newBuilder(uri("/api/auth/me")).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200,me.statusCode());
        assertEquals(email,json.readTree(me.body()).path("email").asText());
        String sessionId = sessionCookie.substring("JSESSIONID=".length()).split(";", 2)[0];
        var urlSession = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        uri("/api/auth/me;jsessionid=" + sessionId)).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, urlSession.statusCode(), "登录凭据只能通过 Cookie 传递，不能放入 URL");
        var rejected = client.send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                .header("Origin","https://untrusted.example.invalid")
                .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403,rejected.statusCode());
        assertEquals(204,post(client,"/api/auth/logout",Map.of()).statusCode());
        var after = client.send(HttpRequest.newBuilder(uri("/api/auth/me")).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401,after.statusCode());
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String,?> body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json")
                .header("Origin","http://localhost:"+port).timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:"+port+path); }
}
