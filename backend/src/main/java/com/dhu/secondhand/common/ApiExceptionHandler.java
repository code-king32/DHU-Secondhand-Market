package com.dhu.secondhand.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String message, String code) {
        public ErrorResponse(String message) { this(message, null); }
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ErrorResponse> limited(RateLimitException exception) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(exception.getRetryAfter()))
                .body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> business(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorResponse(exception.getMessage(), exception.getCode()));
    }

    @ExceptionHandler({BindException.class, HttpMessageNotReadableException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("请求参数不正确"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException exception) {
        var methods = exception.getSupportedHttpMethods();
        var builder = ResponseEntity.status(405);
        if (methods != null) builder.allow(methods.toArray(org.springframework.http.HttpMethod[]::new));
        return builder.body(new ErrorResponse("请求方法不支持"));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMedia(org.springframework.web.HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).body(new ErrorResponse("请求内容类型不支持"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(new ErrorResponse("每张图片不能超过 5 MB"));
    }
}
