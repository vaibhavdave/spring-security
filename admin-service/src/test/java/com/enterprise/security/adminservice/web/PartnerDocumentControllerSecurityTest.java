package com.enterprise.security.adminservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo API key is set in application-test.yml's security.api-keys.partner-acme.key. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerDocumentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * A missing or wrong key leaves the request anonymous rather than authenticated-but-lacking-
     * ROLE_PARTNER, so Spring Security's ExceptionTranslationFilter treats it as a missing
     * credential (401 via RestAuthenticationEntryPoint), not a permission failure (403).
     */
    @Test
    void missingApiKeyIsRejected() throws Exception {
        mockMvc.perform(get("/partner/documents/public"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongApiKeyIsRejected() throws Exception {
        mockMvc.perform(get("/partner/documents/public").header("X-API-Key", "not-the-right-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validApiKeyIsAccepted() throws Exception {
        mockMvc.perform(get("/partner/documents/public").header("X-API-Key", "test-partner-key"))
                .andExpect(status().isOk());
    }
}
