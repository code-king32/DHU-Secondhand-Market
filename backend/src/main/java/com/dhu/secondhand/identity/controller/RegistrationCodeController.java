package com.dhu.secondhand.identity.controller;

import com.dhu.secondhand.identity.dto.SendRegistrationCodeRequest;
import com.dhu.secondhand.identity.dto.SendRegistrationCodeResponse;
import com.dhu.secondhand.identity.service.RegistrationCodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class RegistrationCodeController {

    private final RegistrationCodeService service;

    public RegistrationCodeController(RegistrationCodeService service) {
        this.service = service;
    }

    @PostMapping("/registration-codes")
    public SendRegistrationCodeResponse send(@Valid @RequestBody SendRegistrationCodeRequest request) {
        return service.send(request.email());
    }
}
