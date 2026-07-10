package com.enterprise.security.userservice.dto;

import com.enterprise.security.userservice.domain.User;

import java.time.Instant;

public record UserResponse(
        String id,
        String username,
        String email,
        String fullName,
        String department,
        String tenantId,
        boolean active,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getDepartment(),
                user.getTenantId(),
                user.isActive(),
                user.getCreatedAt());
    }
}
