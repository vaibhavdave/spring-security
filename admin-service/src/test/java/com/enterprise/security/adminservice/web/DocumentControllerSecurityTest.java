package com.enterprise.security.adminservice.web;

import com.enterprise.security.adminservice.domain.Classification;
import com.enterprise.security.adminservice.dto.CreateDocumentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the whole chain end to end through real HTTP requests: SecurityConfig's route rules,
 * DocumentService's @PreAuthorize/@Secured annotations, and DocumentPermissionEvaluator's ABAC
 * decisions — the same layering a real client would experience, not just the evaluator in
 * isolation (see DocumentPermissionEvaluatorTest for that).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editorCanCreateADocumentInTheirOwnTenant() throws Exception {
        var request = new CreateDocumentRequest("Title", "Body", Classification.INTERNAL);

        mockMvc.perform(post("/api/documents")
                        .with(jwt().jwt(j -> j.subject("editor-1").claim("tenant", "tenant-a").claim("clearance", 3))
                                .authorities(role("EDITOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void plainUserCannotCreateADocument() throws Exception {
        var request = new CreateDocumentRequest("Title", "Body", Classification.INTERNAL);

        mockMvc.perform(post("/api/documents")
                        .with(jwt().jwt(j -> j.subject("user-1").claim("tenant", "tenant-a").claim("clearance", 3))
                                .authorities(role("USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingAcrossTenantsIsForbiddenEvenForAdmin() throws Exception {
        // First, create a document owned by tenant-a.
        var createRequest = new CreateDocumentRequest("Cross-tenant target", "Body", Classification.PUBLIC);
        String responseBody = mockMvc.perform(post("/api/documents")
                        .with(jwt().jwt(j -> j.subject("admin-a").claim("tenant", "tenant-a").claim("clearance", 3))
                                .authorities(role("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(responseBody).get("id").asText();

        // An ADMIN from a *different* tenant must still be denied — tenant isolation beats role.
        mockMvc.perform(get("/api/documents/{id}", id)
                        .with(jwt().jwt(j -> j.subject("admin-b").claim("tenant", "tenant-b").claim("clearance", 3))
                                .authorities(role("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminReadsAcrossTenants() throws Exception {
        var createRequest = new CreateDocumentRequest("Visible to platform admin", "Body", Classification.RESTRICTED);
        String responseBody = mockMvc.perform(post("/api/documents")
                        .with(jwt().jwt(j -> j.subject("admin-a").claim("tenant", "tenant-a").claim("clearance", 3))
                                .authorities(role("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(responseBody).get("id").asText();

        mockMvc.perform(get("/api/documents/{id}", id)
                        .with(jwt().jwt(j -> j.subject("root").claim("tenant", "tenant-z").claim("clearance", 0))
                                .authorities(role("PLATFORM_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void insufficientClearanceIsDeniedRegardlessOfTenantMatch() throws Exception {
        var createRequest = new CreateDocumentRequest("Secret", "Body", Classification.RESTRICTED);
        String responseBody = mockMvc.perform(post("/api/documents")
                        .with(jwt().jwt(j -> j.subject("admin-a").claim("tenant", "tenant-a").claim("clearance", 3))
                                .authorities(role("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(responseBody).get("id").asText();

        mockMvc.perform(get("/api/documents/{id}", id)
                        .with(jwt().jwt(j -> j.subject("low-clearance-user").claim("tenant", "tenant-a").claim("clearance", 0))
                                .authorities(role("USER"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.security.core.GrantedAuthority role(String role) {
        return new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role);
    }
}
