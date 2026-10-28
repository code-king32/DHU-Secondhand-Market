package com.dhu.secondhand;

import com.dhu.secondhand.identity.service.MailCodeCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MailCodeCipherTests {
    @TempDir Path directory;

    @Test
    void newInstanceCanResumeEncryptedJobsButAnotherDeliveryCannotReuseCiphertext() {
        String path = directory.resolve("mail.key").toString();
        String encrypted = new MailCodeCipher(path).encrypt("123456", "delivery-1");
        var restarted = new MailCodeCipher(path);
        assertEquals("123456", restarted.decrypt(encrypted, "delivery-1"));
        assertThrows(IllegalStateException.class, () -> restarted.decrypt(encrypted, "delivery-2"));
        assertNotEquals(encrypted, restarted.encrypt("123456", "delivery-1"));
    }
}
