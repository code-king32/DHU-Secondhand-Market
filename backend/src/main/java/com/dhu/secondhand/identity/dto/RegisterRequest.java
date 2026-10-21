package com.dhu.secondhand.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Pattern(regexp="[0-9]{6}") String code,
        @NotBlank @Size(min=8,max=72) String password,
        @NotBlank @Size(max=50) String nickname) { }
