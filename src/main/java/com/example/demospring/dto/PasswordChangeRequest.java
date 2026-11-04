package com.example.demospring.dto;

public record PasswordChangeRequest(String currentPassword, String newPassword) {
}
