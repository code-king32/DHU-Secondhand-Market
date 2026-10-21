package com.dhu.secondhand.identity.exception;

import org.springframework.http.HttpStatus;

public class RegistrationCodeException extends RuntimeException {

    private final HttpStatus status;
    private final long retryAfterSeconds;

    public RegistrationCodeException(HttpStatus status, String message) {
        this(status, message, 0);
    }

    public RegistrationCodeException(HttpStatus status, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
