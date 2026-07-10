package com.enterprise.security.adminservice.repository;

import com.enterprise.security.adminservice.domain.Classification;
import com.enterprise.security.adminservice.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, String> {

    List<Document> findByTenantId(String tenantId);

    List<Document> findByClassification(Classification classification);
}
