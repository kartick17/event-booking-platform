package com.eventbooking.auth.api.dto;

import com.eventbooking.auth.domain.Role;
import com.eventbooking.auth.domain.User;

import java.util.Set;

public record UserResponse(
        Long id,
        String email,
        String fullName,
        Set<Role> roles
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRoles());
    }
}