package com.dhu.secondhand.common;

import org.springframework.http.HttpStatus;

public class RateLimitException extends ApiException {
    private final long retryAfter;
    public RateLimitException(String message, long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.retryAfter = Math.max(1, retryAfter);
    }
    public long getRetryAfter() { return retryAfter; }
}
