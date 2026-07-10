package com.enterprise.security.adminservice.service;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String id) {
        super("Document not found: " + id);
    }
}
