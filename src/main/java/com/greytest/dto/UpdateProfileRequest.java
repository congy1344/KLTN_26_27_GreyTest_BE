package com.greytest.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "Họ và tên không được để trống")
        @Size(min = 2, max = 100, message = "Họ và tên phải từ 2 đến 100 ký tự")
        String fullName,
        @Size(max = 20, message = "Số điện thoại không được vượt quá 20 ký tự")
        String phoneNumber,
        @Size(max = 10, message = "Giới tính không được vượt quá 10 ký tự")
        String gender,
        LocalDate dateOfBirth) {

    public UpdateProfileRequest(String fullName) {
        this(fullName, null, null, null);
    }
}
