package com.enterprise.security.adminservice.web;

import com.enterprise.security.adminservice.dto.CreateDocumentRequest;
import com.enterprise.security.adminservice.dto.DocumentResponse;
import com.enterprise.security.adminservice.dto.UpdateDocumentRequest;
import com.enterprise.security.adminservice.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse createDocument(@Valid @RequestBody CreateDocumentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return DocumentResponse.from(documentService.createDocument(request, jwt.getSubject(), jwt.getClaimAsString("tenant")));
    }

    @GetMapping("/{id}")
    public DocumentResponse getDocument(@PathVariable String id) {
        return DocumentResponse.from(documentService.getDocument(id));
    }

    @PutMapping("/{id}")
    public DocumentResponse updateDocument(@PathVariable String id, @Valid @RequestBody UpdateDocumentRequest request) {
        return DocumentResponse.from(documentService.updateDocument(id, request));
    }

    @DeleteMapping("/{id}")
    public void deleteDocument(@PathVariable String id) {
        documentService.deleteDocument(id);
    }

    @GetMapping
    public List<DocumentResponse> listDocuments() {
        return documentService.listDocumentsInCurrentTenant().stream().map(DocumentResponse::from).toList();
    }
}
