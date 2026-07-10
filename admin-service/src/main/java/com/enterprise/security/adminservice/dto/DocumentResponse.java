package com.enterprise.security.adminservice.dto;

import com.enterprise.security.adminservice.domain.Classification;
import com.enterprise.security.adminservice.domain.Document;

import java.time.Instant;

public record DocumentResponse(
        String id,
        String tenantId,
        String ownerId,
        Classification classification,
        String title,
        String body,
        Instant createdAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getTenantId(),
                document.getOwnerId(),
                document.getClassification(),
                document.getTitle(),
                document.getBody(),
                document.getCreatedAt());
    }
}
