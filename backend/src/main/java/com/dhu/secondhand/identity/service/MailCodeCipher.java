package com.dhu.secondhand.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class MailCodeCipher {
    private final Path keyFile;
    private SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MailCodeCipher(@Value("${app.mail.key-file:uploads/mail-code.key}") String keyFile) {
        this.keyFile = Path.of(keyFile).toAbsolutePath().normalize();
    }

    // 待投递任务需恢复同一个验证码，因此加密存储；验证码验证仍使用 BCrypt 哈希。
    public String encrypt(String code, String deliveryId) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(deliveryId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(code.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("邮件任务加密失败", exception);
        }
    }

    public String decrypt(String value, String deliveryId) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(deliveryId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("邮件任务解密失败，请检查本地密钥文件", exception);
        }
    }

    private synchronized SecretKeySpec key() {
        if (key != null) return key;
        try {
            Files.createDirectories(keyFile.getParent());
            try (var channel = FileChannel.open(keyFile.resolveSibling(keyFile.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
                if (!Files.exists(keyFile)) {
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW);
                }
                byte[] bytes = Files.readAllBytes(keyFile);
                if (bytes.length != 32) throw new IllegalStateException("邮件任务密钥长度不正确");
                key = new SecretKeySpec(bytes, "AES");
                return key;
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法读取或创建邮件任务密钥", exception);
        }
    }
}
