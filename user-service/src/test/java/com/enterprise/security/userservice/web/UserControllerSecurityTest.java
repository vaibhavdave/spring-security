package com.enterprise.security.userservice.web;

import com.enterprise.security.userservice.domain.User;
import com.enterprise.security.userservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises UserController + UserService's method security through real HTTP requests: ownership
 * (JWT "sub" == path id), RBAC role gates, and the /internal service-to-service route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void seedUsers() {
        userRepository.deleteAll();
        userRepository.save(new User("owner-1", "owner", "owner@example.com", "Owner One", "Eng", "tenant-a"));
        userRepository.save(new User("other-1", "other", "other@example.com", "Other One", "Eng", "tenant-b"));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/owner-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userCanReadTheirOwnProfile() throws Exception {
        mockMvc.perform(get("/api/users/owner-1")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER"))))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotReadAnotherUsersProfileInADifferentTenant() throws Exception {
        mockMvc.perform(get("/api/users/other-1")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAnyProfile() throws Exception {
        mockMvc.perform(get("/api/users/other-1")
                        .with(jwt().jwt(j -> j.subject("admin-1").claim("tenant", "tenant-a")).authorities(role("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void listingUsersRequiresAdminOrSupportRole() throws Exception {
        mockMvc.perform(get("/api/users")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/users")
                        .with(jwt().jwt(j -> j.subject("support-1").claim("tenant", "tenant-a")).authorities(role("SUPPORT"))))
                .andExpect(status().isOk());
    }

    @Test
    void userCanUpdateOwnProfileButNotSomeoneElses() throws Exception {
        String body = """
                {"fullName":"New Name","department":"Sales"}""";

        mockMvc.perform(put("/api/users/owner-1")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER")))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/users/other-1")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER")))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivationIsAdminOnly() throws Exception {
        mockMvc.perform(delete("/api/users/other-1")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/users/other-1")
                        .with(jwt().jwt(j -> j.subject("admin-1").claim("tenant", "tenant-a")).authorities(role("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void internalRouteRejectsOrdinaryUserTokens() throws Exception {
        mockMvc.perform(get("/internal/users/owner-1")
                        .with(jwt().jwt(j -> j.subject("owner-1").claim("tenant", "tenant-a")).authorities(role("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalRouteAcceptsServiceRoleToken() throws Exception {
        mockMvc.perform(get("/internal/users/owner-1")
                        .with(jwt().jwt(j -> j.subject("service-account-order-service")).authorities(role("SERVICE"))))
                .andExpect(status().isOk());
    }

    private static GrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }
}
