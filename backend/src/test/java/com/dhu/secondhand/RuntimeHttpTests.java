package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.mail.username=test@example.com", "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:runtime_http;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password="})
@Sql("/sql/acceptance-flow-schema.sql")
class RuntimeHttpTests {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @MockitoBean private MailService mailService;

    @Test
    void servesRealHttpAndSessionCookiesWithoutLeakingSecrets() throws Exception {
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
        String sessionCookie = loggedIn.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(sessionCookie.toLowerCase().contains("httponly"));
        assertTrue(sessionCookie.toLowerCase().contains("samesite=lax"));
        var me = client.send(HttpRequest.newBuilder(uri("/api/auth/me")).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200,me.statusCode());
        assertEquals(email,json.readTree(me.body()).path("email").asText());
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
