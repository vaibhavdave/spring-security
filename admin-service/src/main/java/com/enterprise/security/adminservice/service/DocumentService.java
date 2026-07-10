package com.enterprise.security.adminservice.service;

import com.enterprise.security.common.audit.Audited;
import com.enterprise.security.common.tenant.TenantContext;
import com.enterprise.security.adminservice.domain.Classification;
import com.enterprise.security.adminservice.domain.Document;
import com.enterprise.security.adminservice.dto.CreateDocumentRequest;
import com.enterprise.security.adminservice.dto.UpdateDocumentRequest;
import com.enterprise.security.adminservice.repository.DocumentRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('EDITOR')")
    @Audited(action = "DOCUMENT_CREATE")
    public Document createDocument(CreateDocumentRequest request, String ownerId, String tenantId) {
        Document document = new Document(UUID.randomUUID().toString(), tenantId, ownerId,
                request.classification(), request.title(), request.body());
        return documentRepository.save(document);
    }

    /**
     * hasPermission delegates to DocumentPermissionEvaluator, which enforces tenant isolation and
     * clearance-vs-classification (ABAC) ahead of this method ever running.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(#id, 'Document', 'READ')")
    public Document getDocument(String id) {
        return documentRepository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @PreAuthorize("hasPermission(#id, 'Document', 'WRITE')")
    @Audited(action = "DOCUMENT_UPDATE")
    public Document updateDocument(String id, UpdateDocumentRequest request) {
        Document document = documentRepository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        document.setTitle(request.title());
        document.setBody(request.body());
        return documentRepository.save(document);
    }

    @PreAuthorize("hasPermission(#id, 'Document', 'WRITE')")
    @Audited(action = "DOCUMENT_DELETE")
    public void deleteDocument(String id) {
        if (!documentRepository.existsById(id)) {
            throw new DocumentNotFoundException(id);
        }
        documentRepository.deleteById(id);
    }

    /** RBAC gate first, then always scoped to the caller's own tenant — ABAC applied in bulk. */
    @Transactional(readOnly = true)
    @Secured({"ROLE_ADMIN", "ROLE_SUPPORT", "ROLE_EDITOR"})
    public List<Document> listDocumentsInCurrentTenant() {
        return documentRepository.findByTenantId(TenantContext.get());
    }

    /** Backing the API-key-authenticated partner feed: only ever the PUBLIC classification. */
    @Transactional(readOnly = true)
    public List<Document> listPublicDocuments() {
        return documentRepository.findByClassification(Classification.PUBLIC);
    }
}
