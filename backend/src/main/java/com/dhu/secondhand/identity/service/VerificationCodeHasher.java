package com.dhu.secondhand.identity.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class VerificationCodeHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String code) {
        return encoder.encode(code);
    }

    public boolean matches(String code, String hash) {
        return encoder.matches(code, hash);
    }
}
