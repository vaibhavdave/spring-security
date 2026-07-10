package com.enterprise.security.userservice.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 200) String fullName,
        @Size(max = 100) String department
) {
}
