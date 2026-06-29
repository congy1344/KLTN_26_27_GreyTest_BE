package com.greytest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email(message = "Email khong hop le")
        @NotBlank(message = "Email la bat buoc")
        String email,
        @Size(min = 6, message = "Mat khau phai co toi thieu 6 ky tu")
        String password,
        @NotBlank(message = "Ho ten la bat buoc")
        String fullName) {
}
