package com.enterprise.security.orderservice.web;

import com.enterprise.security.orderservice.client.RemoteUser;
import com.enterprise.security.orderservice.client.UserServiceClient;
import com.enterprise.security.orderservice.domain.Order;
import com.enterprise.security.orderservice.domain.OrderStatus;
import com.enterprise.security.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserServiceClient is mocked here rather than exercised for real: it does a live OAuth2
 * client-credentials + HTTP round trip to user-service, which belongs in a dedicated
 * Testcontainers-based integration test, not a fast unit-style MockMvc test of order-service's
 * own authorization rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private UserServiceClient userServiceClient;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        when(userServiceClient.getUser(anyString())).thenReturn(new RemoteUser("owner-1", "tenant-a", true));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void userCanCreateAnOrderForThemselves() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(role("USER")))
                        .contentType("application/json")
                        .content("""
                                {"item":"widget","quantity":2}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void creatingAnOrderRejectsAnInactiveOwner() throws Exception {
        when(userServiceClient.getUser(anyString())).thenReturn(new RemoteUser("owner-1", "tenant-a", false));

        mockMvc.perform(post("/api/orders")
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(role("USER")))
                        .contentType("application/json")
                        .content("""
                                {"item":"widget","quantity":2}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void ownerCanReadTheirOwnOrderButNotSomeoneElses() throws Exception {
        Order order = orderRepository.save(new Order(UUID.randomUUID().toString(), "owner-1", "tenant-a", "widget", 1, OrderStatus.CREATED));

        mockMvc.perform(get("/api/orders/{id}", order.getId())
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(role("USER"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", order.getId())
                        .with(jwt().jwt(j -> j.subject("someone-else")).authorities(role("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminOrSupportCanCancelAnOrder() throws Exception {
        Order order = orderRepository.save(new Order(UUID.randomUUID().toString(), "owner-1", "tenant-a", "widget", 1, OrderStatus.CREATED));

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getId())
                        .with(jwt().jwt(j -> j.subject("owner-1")).authorities(role("USER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/orders/{id}/cancel", order.getId())
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(role("ADMIN"))))
                .andExpect(status().isOk());
    }

    private static GrantedAuthority role(String role) {
        return new SimpleGrantedAuthority("ROLE_" + role);
    }
}
