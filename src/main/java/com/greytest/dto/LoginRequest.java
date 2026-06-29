package com.greytest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email(message = "Email khong hop le")
        @NotBlank(message = "Email la bat buoc")
        String email,
        @NotBlank(message = "Mat khau la bat buoc")
        String password) {
}
