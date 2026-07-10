package com.enterprise.security.adminservice.security;

import com.enterprise.security.adminservice.domain.Document;
import com.enterprise.security.adminservice.repository.DocumentRepository;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * Attribute-based access control layered on top of role checks: even an ADMIN in one tenant
 * cannot read another tenant's documents (tenant isolation), and even within the caller's own
 * tenant, the document's classification must not exceed the caller's "clearance" JWT claim
 * (clearance-based access, independent of role). ROLE_PLATFORM_ADMIN is the one deliberate
 * escape hatch, modelling a platform-operations role that spans tenants.
 *
 * Returning false for a missing/inaccessible document (rather than distinguishing 404 from 403)
 * is intentional: it avoids leaking a resource's existence to a caller who isn't allowed to see it.
 */
@Component
public class DocumentPermissionEvaluator implements PermissionEvaluator {

    private final DocumentRepository documentRepository;

    public DocumentPermissionEvaluator(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (!(targetDomainObject instanceof Document document)) {
            return false;
        }
        return checkAccess(authentication, document, permission.toString());
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (!"Document".equals(targetType) || targetId == null) {
            return false;
        }
        return documentRepository.findById(targetId.toString())
                .map(document -> checkAccess(authentication, document, permission.toString()))
                .orElse(false);
    }

    private boolean checkAccess(Authentication authentication, Document document, String permission) {
        if (hasRole(authentication, "PLATFORM_ADMIN")) {
            return true;
        }
        if (!document.getTenantId().equals(tenantClaim(authentication))) {
            return false;
        }
        if (clearanceClaim(authentication) < document.getClassification().ordinal()) {
            return false;
        }
        if ("WRITE".equalsIgnoreCase(permission)) {
            return hasRole(authentication, "ADMIN")
                    || hasRole(authentication, "EDITOR")
                    || document.getOwnerId().equals(authentication.getName());
        }
        return true;
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + role)::equals);
    }

    private String tenantClaim(Authentication authentication) {
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt.getClaimAsString("tenant") : null;
    }

    private int clearanceClaim(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return 0;
        }
        Object clearance = jwt.getClaim("clearance");
        if (clearance instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
