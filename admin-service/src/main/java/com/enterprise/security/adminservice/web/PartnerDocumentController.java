package com.enterprise.security.adminservice.web;

import com.enterprise.security.adminservice.dto.DocumentResponse;
import com.enterprise.security.adminservice.service.DocumentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * External-partner surface authenticated via X-API-Key (see common-security's ApiKeyAuthFilter)
 * rather than a Keycloak-issued JWT — models a legacy/B2B integration that can't do OAuth2.
 * Method-level @PreAuthorize backs up the route-level rule in SecurityConfig.
 */
@RestController
@RequestMapping("/partner/documents")
public class PartnerDocumentController {

    private final DocumentService documentService;

    public PartnerDocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/public")
    @PreAuthorize("hasRole('PARTNER')")
    public List<DocumentResponse> listPublicDocuments() {
        return documentService.listPublicDocuments().stream().map(DocumentResponse::from).toList();
    }
}
