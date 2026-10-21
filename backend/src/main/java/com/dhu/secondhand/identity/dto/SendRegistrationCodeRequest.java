package com.dhu.secondhand.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendRegistrationCodeRequest(
        @NotBlank @Email @Size(max = 254) String email) {
}
