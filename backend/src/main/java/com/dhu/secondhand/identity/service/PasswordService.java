package com.dhu.secondhand.identity.service;

import com.dhu.secondhand.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public class PasswordService {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String dummyHash = encoder.encode("unavailable-account-password");

    public String hash(String password) {
        validate(password);
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        return encoder.matches(password, hash == null || hash.isBlank() ? dummyHash : hash);
    }

    public void validate(String password) {
        if (password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码至少8个字符，UTF-8编码不超过72字节");
        }
    }
}
