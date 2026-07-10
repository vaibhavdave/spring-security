package com.enterprise.security.adminservice.dto;

import com.enterprise.security.adminservice.domain.Classification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDocumentRequest(
        @NotBlank String title,
        @NotBlank String body,
        @NotNull Classification classification
) {
}
