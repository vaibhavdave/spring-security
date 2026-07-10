package com.enterprise.security.adminservice.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDocumentRequest(
        @NotBlank String title,
        @NotBlank String body
) {
}
