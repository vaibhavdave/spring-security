package com.enterprise.security.adminservice.security;

import com.enterprise.security.adminservice.domain.Classification;
import com.enterprise.security.adminservice.domain.Document;
import com.enterprise.security.adminservice.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentPermissionEvaluatorTest {

    @Mock
    private DocumentRepository documentRepository;

    private DocumentPermissionEvaluator evaluator;

    private final Document tenantAConfidential =
            new Document("doc-1", "tenant-a", "owner-1", Classification.CONFIDENTIAL, "t", "b");
    private final Document tenantAPublic =
            new Document("doc-2", "tenant-a", "owner-1", Classification.PUBLIC, "t", "b");
    private final Document tenantBPublic =
            new Document("doc-3", "tenant-b", "owner-2", Classification.PUBLIC, "t", "b");

    @Test
    void deniesAccessAcrossTenantsEvenForAdmin() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        var admin = token("caller", "tenant-a", 3, "ADMIN");

        boolean allowed = evaluator.hasPermission(admin, tenantBPublic, "READ");

        assertThat(allowed).isFalse();
    }

    @Test
    void platformAdminBypassesTenantIsolation() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        var platformAdmin = token("caller", "tenant-a", 0, "PLATFORM_ADMIN");

        boolean allowed = evaluator.hasPermission(platformAdmin, tenantBPublic, "READ");

        assertThat(allowed).isTrue();
    }

    @Test
    void deniesReadWhenClearanceBelowClassification() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        var lowClearanceUser = token("caller", "tenant-a", 0, "USER");

        boolean allowed = evaluator.hasPermission(lowClearanceUser, tenantAConfidential, "READ");

        assertThat(allowed).isFalse();
    }

    @Test
    void allowsReadWhenClearanceMeetsClassification() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        var sufficientClearanceUser = token("caller", "tenant-a", 2, "USER");

        boolean allowed = evaluator.hasPermission(sufficientClearanceUser, tenantAConfidential, "READ");

        assertThat(allowed).isTrue();
    }

    @Test
    void writeRequiresOwnershipOrPrivilegedRoleEvenWithSufficientClearance() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        var otherUserSameTenant = token("someone-else", "tenant-a", 3, "USER");

        boolean allowed = evaluator.hasPermission(otherUserSameTenant, tenantAPublic, "WRITE");

        assertThat(allowed).isFalse();
    }

    @Test
    void ownerMayWriteTheirOwnDocument() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        var owner = token("owner-1", "tenant-a", 3, "USER");

        boolean allowed = evaluator.hasPermission(owner, tenantAPublic, "WRITE");

        assertThat(allowed).isTrue();
    }

    @Test
    void missingDocumentDeniesRatherThanThrowing() {
        evaluator = new DocumentPermissionEvaluator(documentRepository);
        when(documentRepository.findById("missing")).thenReturn(Optional.empty());
        var admin = token("caller", "tenant-a", 3, "ADMIN");

        boolean allowed = evaluator.hasPermission(admin, "missing", "Document", "READ");

        assertThat(allowed).isFalse();
    }

    private JwtAuthenticationToken token(String subject, String tenant, int clearance, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("tenant", tenant)
                .claim("clearance", clearance)
                .build();
        List<GrantedAuthority> authorities = List.of(roles).stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
