package com.example.demospring.dto;

import com.example.demospring.entity.User;
import java.util.List;

public record UserResponse(String id, String username, List<String> roles) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), List.copyOf(user.getRoles()));
    }
}
