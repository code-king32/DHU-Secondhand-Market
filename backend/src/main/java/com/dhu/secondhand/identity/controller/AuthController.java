package com.dhu.secondhand.identity.controller;

import com.dhu.secondhand.account.dto.UserView;
import com.dhu.secondhand.account.mapper.UserMapper;
import com.dhu.secondhand.common.SessionAuth;
import com.dhu.secondhand.common.AbuseProtection;
import com.dhu.secondhand.common.ClientIpResolver;
import com.dhu.secondhand.identity.dto.LoginRequest;
import com.dhu.secondhand.identity.dto.RegisterRequest;
import com.dhu.secondhand.identity.service.AccountAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountAuthService service;
    private final SessionAuth auth;
    private final UserMapper users;
    private final AbuseProtection protection;
    private final ClientIpResolver clientIps;

    public AuthController(AccountAuthService service, SessionAuth auth, UserMapper users, AbuseProtection protection,
                          ClientIpResolver clientIps) {
        this.service = service; this.auth = auth; this.users = users; this.protection = protection;
        this.clientIps = clientIps;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request) { return service.register(request); }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        protection.beforeLogin(clientIps.resolve(request), body.email());
        UserView user = service.login(body);
        UserView result = service.openSession(user.id(), request);
        protection.loginSucceeded(body.email());
        return result;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserView me(HttpServletRequest request) {
        return UserView.from(users.selectById(auth.requireUserId(request)));
    }
}
