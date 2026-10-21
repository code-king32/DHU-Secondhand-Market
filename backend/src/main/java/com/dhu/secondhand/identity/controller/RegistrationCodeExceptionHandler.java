package com.dhu.secondhand.identity.controller;

import com.dhu.secondhand.identity.exception.RegistrationCodeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RegistrationCodeController.class)
public class RegistrationCodeExceptionHandler {

    @ExceptionHandler(RegistrationCodeException.class)
    public ResponseEntity<ErrorResponse> handleBusinessError(RegistrationCodeException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.getRetryAfterSeconds() > 0) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()));
        }
        return new ResponseEntity<>(new ErrorResponse(exception.getMessage()), headers, exception.getStatus());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("请提供格式正确的校园邮箱"));
    }

    public record ErrorResponse(String message) {
    }
}
