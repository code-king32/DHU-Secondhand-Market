package com.dhu.secondhand.identity.controller;

import com.dhu.secondhand.identity.dto.SendRegistrationCodeRequest;
import com.dhu.secondhand.identity.dto.SendRegistrationCodeResponse;
import com.dhu.secondhand.identity.service.RegistrationCodeService;
import com.dhu.secondhand.identity.service.MailDeliveryService;
import com.dhu.secondhand.common.AbuseProtection;
import com.dhu.secondhand.common.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistrationCodeController {

    private final RegistrationCodeService service;
    private final MailDeliveryService deliveries;
    private final AbuseProtection protection;
    private final ClientIpResolver clientIps;

    public RegistrationCodeController(RegistrationCodeService service, MailDeliveryService deliveries, AbuseProtection protection,
                                      ClientIpResolver clientIps) {
        this.service = service;
        this.deliveries = deliveries;
        this.protection = protection;
        this.clientIps = clientIps;
    }

    @PostMapping("/registration-codes")
    public ResponseEntity<SendRegistrationCodeResponse> send(@Valid @RequestBody SendRegistrationCodeRequest body,
                                                            HttpServletRequest request) {
        protection.beforeMail(clientIps.resolve(request), body.email());
        var response = service.send(body.email());
        int status = switch (response.status()) { case "SENT" -> 200; case "FAILED" -> 502; default -> 202; };
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/registration-codes/{id}")
    public SendRegistrationCodeResponse status(@PathVariable String id) {
        return deliveries.status(id);
    }
}
