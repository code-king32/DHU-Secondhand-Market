package com.dhu.secondhand.identity.dto;

public record SendRegistrationCodeResponse(
        String message, long expiresInSeconds, long resendAfterSeconds, String deliveryId, String status) {
}
