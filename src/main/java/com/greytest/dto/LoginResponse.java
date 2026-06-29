package com.greytest.dto;

public record LoginResponse(String token, AuthUserDto user) {
}
