package com.dhu.secondhand;

import com.dhu.secondhand.account.controller.AccountController;
import com.dhu.secondhand.common.ApiException;
import com.dhu.secondhand.common.SessionAuth;
import com.dhu.secondhand.identity.service.PasswordService;
import com.dhu.secondhand.identity.controller.AuthController;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.jobs.enabled=false", "app.abuse.enabled=false", "app.mail.key-file=target/test-mail-code.key",
        "spring.mail.username=test@example.com", "spring.mail.test-connection=false",
        "spring.datasource.url=jdbc:h2:mem:account_closure_concurrency;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@Sql("/sql/marketplace-schema.sql")
class AccountClosureConcurrencyTests {
    private static final String PASSWORD = "ClosurePassword123!";
    @Autowired AccountController accounts;
    @Autowired AuthController authentication;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordService passwords;
    @MockitoSpyBean SessionAuth auth;
    @MockitoSpyBean TransactionTemplate transactions;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setup() {
        jdbc.update("UPDATE app_user SET password_hash=? WHERE id=1", passwords.hash(PASSWORD));
        jdbc.update("INSERT INTO market_goods(id,seller_id,title,description,price,image_urls) VALUES(10,1,'教材','说明',20,'[]')");
    }

    @Test
    void logoutAfterCommitCannotTurnSuccessfulClosureIntoAnError() {
        var request = request();
        HttpSession session = request.getSession(false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            session.invalidate();
            return null;
        }).when(transactions).executeWithoutResult(any());
        assertEquals(HttpStatus.NO_CONTENT, accounts.close(new AccountController.CloseRequest(PASSWORD), request).getStatusCode());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM app_user WHERE id=1", Integer.class));
        assertEquals("OFF_SHELF", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=10", String.class));
    }

    @Test
    void invalidationAfterSessionLookupDoesNotFailAnAlreadyCommittedClosure() {
        var request = request();
        HttpSession session = request.getSession(false);
        var stale = mock(HttpSession.class);
        doThrow(new IllegalStateException("Already invalidated")).when(stale).invalidate();
        var committed = new AtomicBoolean();
        var racingRequest = spy(request);
        doAnswer(invocation -> committed.get() ? stale : session).when(racingRequest).getSession(false);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            session.invalidate();
            committed.set(true);
            return null;
        }).when(transactions).executeWithoutResult(any());
        assertEquals(HttpStatus.NO_CONTENT,
                accounts.close(new AccountController.CloseRequest(PASSWORD), racingRequest).getStatusCode());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM app_user WHERE id=1", Integer.class));
        verify(stale).invalidate();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void accountStateChangesAfterAuthenticationAreRecheckedUnderTheRowLock(int status) {
        var request = request();
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long id = (long) invocation.callRealMethod();
            if (first.getAndSet(false)) {
                jdbc.update("UPDATE app_user SET status=?,session_version=session_version+1 WHERE id=?", status, id);
            }
            return id;
        }).when(auth).requireUserId(request);
        ApiException exception = assertThrows(ApiException.class,
                () -> accounts.close(new AccountController.CloseRequest(PASSWORD), request));
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals(status, jdbc.queryForObject("SELECT status FROM app_user WHERE id=1", Integer.class));
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=10", String.class));
        assertTrue(passwords.matches(PASSWORD, jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id=1", String.class)));
    }

    @Test
    void sessionRevocationAfterAuthenticationPreventsClosureEvenWhenTheAccountIsActive() {
        var request = request();
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long id = (long) invocation.callRealMethod();
            if (first.getAndSet(false)) jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?", id);
            return id;
        }).when(auth).requireUserId(request);
        ApiException exception = assertThrows(ApiException.class,
                () -> accounts.close(new AccountController.CloseRequest(PASSWORD), request));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("SESSION_EXPIRED", exception.getCode());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM app_user WHERE id=1", Integer.class));
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM market_goods WHERE id=10", String.class));
    }

    @Test
    void sessionRevocationAfterAuthenticationAlsoPreventsProfileChanges() {
        var request = request();
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            long id = (long) invocation.callRealMethod();
            if (first.getAndSet(false)) jdbc.update("UPDATE app_user SET session_version=session_version+1 WHERE id=?", id);
            return id;
        }).when(auth).requireUserId(request);
        String nickname = jdbc.queryForObject("SELECT nickname FROM app_user WHERE id=1", String.class);
        ApiException exception = assertThrows(ApiException.class,
                () -> accounts.update(new AccountController.ProfileRequest("不应保存"), request));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("SESSION_EXPIRED", exception.getCode());
        assertEquals(nickname, jdbc.queryForObject("SELECT nickname FROM app_user WHERE id=1", String.class));
    }

    @Test
    void logoutRemainsIdempotentWhenAnotherRequestAlreadyInvalidatedTheSession() {
        var stale = mock(HttpSession.class);
        doThrow(new IllegalStateException("Already invalidated")).when(stale).invalidate();
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getSession(false)).thenReturn(stale);
        assertEquals(HttpStatus.NO_CONTENT, authentication.logout(request).getStatusCode());
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        session.setAttribute("sessionVersion", 0);
        request.setSession(session);
        return request;
    }
}
