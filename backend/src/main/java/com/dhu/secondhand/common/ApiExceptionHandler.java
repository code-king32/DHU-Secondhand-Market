package com.dhu.secondhand.common;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {
    public record ErrorResponse(String message) { }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ErrorResponse> limited(RateLimitException exception) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(exception.getRetryAfter()))
                .body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> business(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler({BindException.class, HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("请求参数不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(new ErrorResponse("每张图片不能超过 5 MB"));
    }
}
